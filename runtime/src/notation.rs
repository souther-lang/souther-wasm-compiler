//! A `String` as notation-199x reads it.
//!
//! What a string means — its order, its length, its case, its canonical form, which characters are
//! white space, which text is a day or a moment, which strings a pattern accepts — is a rule
//! Souther shares with Raoh, and notation-199x answers it. The JVM backend asks the library's Java
//! implementation and this runtime asks its Rust one, so the two backends answer from one account
//! rather than from two that are kept in step by hand.
//!
//! This module is where a `String` cell becomes the `&str` the library takes and the library's
//! answer becomes a cell again. Everything else here is what Souther adds: how long a `String` may
//! be, and that every `String` is canonical.

use notation199x::Form;

use crate::value::{__souther_string, __souther_string_bytes, __souther_string_length};
use crate::{abort, REASON_REQUIRED_FORM_HAS_NO_PLACE};

/// The longest text, in code points, a Souther `String` holds (spec §what-a-string-holds): the
/// number `souther.runtime.Strings.LONGEST_TEXT` states.
pub const LONGEST_TEXT: usize = (1 << 28) - 1;

/// The text a `String` cell holds.
///
/// Every `String` was let in by [`admitted`] or built here from ones that were, so its bytes are
/// UTF-8 and are not asked again.
pub unsafe fn str_of(text: u32) -> &'static str {
    str_at(__souther_string_bytes(text), __souther_string_length(text))
}

/// The text at a run of bytes this runtime already holds as UTF-8.
pub unsafe fn str_at(at: u32, length: u32) -> &'static str {
    core::str::from_utf8_unchecked(core::slice::from_raw_parts(at as *const u8, length as usize))
}

/// A `String` cell holding `text` as it is.
pub unsafe fn made(text: &str) -> u32 {
    __souther_string(text.as_ptr() as u32, text.len() as u32)
}

/// Ends the call where a text `code_points` long has no place: asked of the text an operation is
/// defined as canonicalizing before it is built, so nothing no `String` holds is built.
pub unsafe fn holds(code_points: u64) {
    if code_points > LONGEST_TEXT as u64 {
        abort(REASON_REQUIRED_FORM_HAS_NO_PLACE, 0, code_points, LONGEST_TEXT as u64);
    }
}

/// `text` canonicalized to NFC, as a `String` cell, or an end to the call where the canonical form
/// has no place. NFC is closed neither under joining two texts nor under mapping case, so every
/// operation that does either answers through here.
pub unsafe fn canonical(text: &str) -> u32 {
    match notation199x::normalize_within(Form::Nfc, text, LONGEST_TEXT) {
        Some(held) => made(&held),
        None => abort(REASON_REQUIRED_FORM_HAS_NO_PLACE, 0, text.len() as u64, LONGEST_TEXT as u64),
    }
}

/// Whether text that starts here can follow canonical text and leave the two canonical together.
///
/// A code point below U+0300 is a starter that composes with nothing before it, which is what
/// notation-199x's own normalization takes such a run as without working it out. U+0300 is written
/// `CC 80`, so a first byte below `CC` is a code point below it, and so is no byte at all.
pub fn starts_stable(text: &str) -> bool {
    text.as_bytes().first().map_or(true, |&first| first < 0xcc)
}

/// Canonical texts joined in order, canonicalized where a seam needs it, as a `String` cell, or an
/// end to the call where the answer has no place.
///
/// Where every piece after the first starts with a code point that composes with nothing before it,
/// the pieces joined are already canonical and are copied once. Where their bytes also fit in what
/// a `String` holds, so do their code points, since a code point is at least a byte.
pub unsafe fn joined(pieces: &[&str]) -> u32 {
    let bytes: u64 = pieces.iter().map(|piece| piece.len() as u64).sum();
    if bytes <= LONGEST_TEXT as u64 && pieces.iter().skip(1).all(|piece| starts_stable(piece)) {
        return crate::value::__souther_string_of(pieces);
    }
    holds(pieces.iter().map(|piece| notation199x::scalar_count(piece) as u64).sum());
    canonical(&pieces.concat())
}

/// Text arriving from outside, as the `String` it is, or nothing where it is not one: its bytes
/// are not UTF-8, or its canonical form is longer than a `String` holds.
///
/// The one way text becomes a value. Refused and not repaired, as `Strings.admission` refuses it.
pub unsafe fn admitted(at: u32, length: u32) -> Option<u32> {
    let bytes = core::slice::from_raw_parts(at as *const u8, length as usize);
    let text = core::str::from_utf8(bytes).ok()?;
    // Nothing below U+0300 changes under NFC, and that many bytes are no more code points.
    if bytes.len() <= LONGEST_TEXT && bytes.iter().all(|&byte| byte < 0xcc) {
        return Some(made(text));
    }
    notation199x::normalize_within(Form::Nfc, text, LONGEST_TEXT).map(|held| made(&held))
}

/// How many code points a `String` holds.
pub unsafe fn length_of(text: u32) -> u32 {
    notation199x::scalar_count(str_of(text)) as u32
}
