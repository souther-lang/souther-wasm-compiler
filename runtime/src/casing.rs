//! `String.lowercase`/`String.uppercase`: Unicode 18.0.0's default case conversion, untailored
//! (issue #21, mirroring `souther-runtime`'s ADR-0119).
//!
//! The mapping and property data this reads ([`crate::casing_data`]) is generated from the Unicode
//! Character Database; this file owns only the algorithm that reads it — casing is a `String ->
//! String` operation, not a per-`char` one, because Greek capital sigma's lowercase form depends on
//! whether it sits at the end of a cased run (`Final_Sigma`). Rust's own `char::to_lowercase()`/
//! `to_uppercase()` cannot express that and pin no Unicode version, so neither is used here —
//! `char::encode_utf8` still is, for UTF-8 representation, which is not casing semantics.
//!
//! `Final_Sigma` is tracked with one flag (`cased_before`) carried forward across a single scan of
//! the input, rather than materializing the string's code points to scan backward from each sigma:
//! this crate is `#![no_std]` with no `alloc`, and a single forward pass is sufficient — the state
//! a Final_Sigma decision needs is exactly "was the nearest non-`Case_Ignorable` code point before
//! this one `Cased`", which one flag holds. The one place this still looks ahead is the code points
//! after a sigma, to find the next non-`Case_Ignorable` one; a `Case_Ignorable` run is bounded by
//! the cased letters on either side of it, so this does not turn the whole scan quadratic.

use crate::casing_data::{Mapping, CASED, CASE_IGNORABLE, FINAL_SIGMA, LOWER, UPPER};
use crate::kernel::{character_width, code_point_at};
use crate::value::{__souther_string, __souther_string_bytes, __souther_string_length};
use crate::{abort, alloc, next_free, REASON_INVARIANT_VIOLATION};

/// `String.lowercase(s)`.
pub(crate) unsafe fn lowercase(text: u32) -> u32 {
    recase(text, true)
}

/// `String.uppercase(s)`.
pub(crate) unsafe fn uppercase(text: u32) -> u32 {
    recase(text, false)
}

/// GREEK CAPITAL LETTER SIGMA — the one code point Unicode 18.0.0's `Final_Sigma` condition names.
const GREEK_CAPITAL_SIGMA: u32 = 0x3A3;

unsafe fn recase(text: u32, lower: bool) -> u32 {
    let bytes = __souther_string_bytes(text);
    let length = __souther_string_length(text);
    let out = next_free();
    let mut written = 0;
    let mut at = 0;
    let mut cased_before = false;
    while at < length {
        let width = character_width(core::ptr::read((bytes + at) as *const u8));
        let point = code_point_at(bytes + at, width);
        let next = at + width;

        if lower
            && point == GREEK_CAPITAL_SIGMA
            && cased_before
            && !cased_before_next_cased(bytes, next, length)
        {
            // `lookup` cannot miss here: GREEK_CAPITAL_SIGMA is the code point FINAL_SIGMA is
            // keyed on, and this branch is only taken when `point == GREEK_CAPITAL_SIGMA`.
            written += put_mapped(lookup(FINAL_SIGMA, point).unwrap_or_else(|| {
                abort(REASON_INVARIANT_VIOLATION, 0, point as u64, 0)
            }));
        } else {
            match lookup(if lower { LOWER } else { UPPER }, point) {
                Some(mapped) => written += put_mapped(mapped),
                None => written += put_code_point(point),
            }
        }

        // The original code point decides `cased_before`, not its mapping: Final_Sigma is stated
        // over the input string's own Cased/Case_Ignorable runs, not over what casing produces.
        if !is_case_ignorable(point) {
            cased_before = is_cased(point);
        }
        at = next;
    }
    __souther_string(out, written)
}

/// Whether the nearest code point after `at`, skipping `Case_Ignorable` ones, is `Cased` — the
/// forward half of `Final_Sigma`: "not immediately followed, skipping Case_Ignorable, by Cased".
/// A code point past the end of the string does not satisfy it.
unsafe fn cased_before_next_cased(bytes: u32, mut at: u32, length: u32) -> bool {
    while at < length {
        let width = character_width(core::ptr::read((bytes + at) as *const u8));
        let point = code_point_at(bytes + at, width);
        if !is_case_ignorable(point) {
            return is_cased(point);
        }
        at += width;
    }
    false
}

/// Writes one mapped code point's [`Mapping`] result, in order, and answers how many bytes that
/// took. `mapped.from` is not used here — the caller already matched on the code point that chose
/// this mapping.
unsafe fn put_mapped(mapped: &Mapping) -> u32 {
    let mut written = 0;
    for &cp in mapped.to {
        written += put_code_point(cp);
    }
    written
}

/// Writes one code point onto the arena's top, encoded as UTF-8, and answers how many bytes that
/// took — the same contiguous-piece contract [`recase`]'s caller relies on, kept local to this
/// file: nothing this module calls between one `put_code_point` and the next allocates.
unsafe fn put_code_point(cp: u32) -> u32 {
    // Every code point reaching here came either from this string's own valid UTF-8 (decoded by
    // `code_point_at`) or from a generated table entry, both already-valid Unicode scalar values.
    let held = match char::from_u32(cp) {
        Some(held) => held,
        None => abort(REASON_INVARIANT_VIOLATION, 0, cp as u64, 0),
    };
    let mut room = [0u8; 4];
    let written = held.encode_utf8(&mut room).len();
    let at = alloc(written as u32);
    core::ptr::copy_nonoverlapping(room.as_ptr(), at as *mut u8, written);
    written as u32
}

fn lookup(table: &'static [Mapping], cp: u32) -> Option<&'static Mapping> {
    table
        .binary_search_by_key(&cp, |m| m.from)
        .ok()
        .map(|i| &table[i])
}

fn is_cased(cp: u32) -> bool {
    in_ranges(CASED, cp)
}

fn is_case_ignorable(cp: u32) -> bool {
    in_ranges(CASE_IGNORABLE, cp)
}

fn in_ranges(ranges: &'static [(u32, u32)], cp: u32) -> bool {
    match ranges.binary_search_by_key(&cp, |&(start, _)| start) {
        Ok(_) => true,
        Err(0) => false,
        Err(next) => cp <= ranges[next - 1].1,
    }
}
