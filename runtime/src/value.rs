//! A Souther value while a call is running, and how one is read out of JSON and written back.
//!
//! # The cell
//!
//! Every value is a cell in the arena, whatever it is:
//!
//! ```text
//! +0  u32 tag
//! +4  u32 length    bytes, for a value that carries bytes
//! +8  payload
//! ```
//!
//! Boxed even where it need not be. An `Int` in a local would be an `i64` and nothing else, but the
//! same `Int` inside a list, a map or an option has to be reachable by a pointer like everything
//! else there, and a representation that changed at the edge of a container would put a conversion
//! at every one of those edges. So the box comes first and unboxing a local is something to add
//! against a measurement, not before one.
//!
//! # Reading
//!
//! What a place holds is decided by what was declared, not by what the document happens to look
//! like. So a reader is asked for by name — read this as an `Int` — and the JSON is what it checks
//! against, rather than the JSON being asked what it is.
//!
//! What a reader does with input it cannot read is end the call. That is not where this is going:
//! a decoder at the boundary answers with the issues it found, at their paths, because malformed
//! input is an expected outcome rather than a fault. Carrying those issues needs a value this
//! runtime does not have yet, and until it does, a refusal says only which reader refused.

use crate::json;
use crate::{abort, alloc, REASON_NOT_WHAT_WAS_DECLARED, REASON_NUMBER_OUT_OF_RANGE};

/// The one value a type with a single value has.
pub const TAG_UNIT: u32 = 0;
/// An `Int`, whose payload is sixty-four bits.
pub const TAG_INT: u32 = 1;
/// A `Bool`, whose payload is one or zero.
pub const TAG_BOOL: u32 = 2;
/// A `String`, whose payload is its UTF-8 bytes.
pub const TAG_STRING: u32 = 3;

const HEADER: usize = 8;

/// The `Int` a cell holds.
#[no_mangle]
pub unsafe extern "C" fn __souther_int_value(cell: u32) -> i64 {
    core::ptr::read_unaligned((cell as usize + HEADER) as *const i64)
}

/// A cell holding an `Int`.
#[no_mangle]
pub unsafe extern "C" fn __souther_int(value: i64) -> u32 {
    let cell = header(TAG_INT, 0);
    let _ = alloc(8);
    core::ptr::write_unaligned((cell as usize + HEADER) as *mut i64, value);
    cell
}

/// The `Bool` a cell holds, as one or zero.
#[no_mangle]
pub unsafe extern "C" fn __souther_bool_value(cell: u32) -> u32 {
    core::ptr::read_unaligned((cell as usize + HEADER) as *const u32)
}

/// A cell holding a `Bool`.
#[no_mangle]
pub unsafe extern "C" fn __souther_bool(value: u32) -> u32 {
    let cell = header(TAG_BOOL, 0);
    let _ = alloc(4);
    core::ptr::write_unaligned((cell as usize + HEADER) as *mut u32, u32::from(value != 0));
    cell
}

/// Where a `String` cell's bytes are.
#[no_mangle]
pub unsafe extern "C" fn __souther_string_bytes(cell: u32) -> u32 {
    cell + HEADER as u32
}

/// How many bytes a `String` cell holds.
#[no_mangle]
pub unsafe extern "C" fn __souther_string_length(cell: u32) -> u32 {
    core::ptr::read_unaligned((cell as usize + 4) as *const u32)
}

/// A cell holding a `String`, copied out of wherever the bytes were.
#[no_mangle]
pub unsafe extern "C" fn __souther_string(pointer: u32, length: u32) -> u32 {
    let cell = header(TAG_STRING, length);
    let _ = alloc(length);
    core::ptr::copy_nonoverlapping(
        pointer as *const u8,
        (cell as usize + HEADER) as *mut u8,
        length as usize,
    );
    cell
}

/// The argument at a position of what a caller handed in.
///
/// A call's arguments are one JSON array, in the order the behavior declares its parameters. An
/// array of another length is not a call of this behavior, so it ends here rather than being read
/// as far as it goes.
#[no_mangle]
pub unsafe extern "C" fn __souther_argument(document: u32, index: u32, expected: u32) -> u32 {
    if json::__souther_json_tag(document) != json::TAG_ARRAY {
        abort(REASON_NOT_WHAT_WAS_DECLARED, 0, json::__souther_json_tag(document) as u64,
              json::TAG_ARRAY as u64);
    }
    let held = json::__souther_json_length(document);
    if held != expected {
        abort(REASON_NOT_WHAT_WAS_DECLARED, 0, held as u64, expected as u64);
    }
    json::__souther_json_element(document, index)
}

/// Reads a JSON value as an `Int`.
///
/// A whole number and nothing else. Digits after a point are a `Decimal`'s and not something to
/// round away here, and a number outside what sixty-four bits hold is not an `Int` at all.
#[no_mangle]
pub unsafe extern "C" fn __souther_read_int(value: u32) -> u32 {
    expect(value, json::TAG_NUMBER);
    let bytes = json::__souther_json_bytes(value) as usize;
    let length = json::__souther_json_length(value) as usize;
    let mut at = 0;
    let negative = core::ptr::read(bytes as *const u8) == b'-';
    if negative {
        at = 1;
    }
    let mut magnitude: u128 = 0;
    while at < length {
        let digit = core::ptr::read((bytes + at) as *const u8);
        if !digit.is_ascii_digit() {
            // A point or an exponent means the document wrote an amount, not a whole number.
            abort(REASON_NOT_WHAT_WAS_DECLARED, 0, json::TAG_NUMBER as u64, TAG_INT as u64);
        }
        magnitude = magnitude * 10 + (digit - b'0') as u128;
        if magnitude > 1u128 << 63 {
            abort(REASON_NUMBER_OUT_OF_RANGE, 0, at as u64, length as u64);
        }
        at += 1;
    }
    if negative {
        if magnitude > 1u128 << 63 {
            abort(REASON_NUMBER_OUT_OF_RANGE, 0, 0, length as u64);
        }
        __souther_int((magnitude as i128).wrapping_neg() as i64)
    } else {
        if magnitude > i64::MAX as u128 {
            abort(REASON_NUMBER_OUT_OF_RANGE, 0, 0, length as u64);
        }
        __souther_int(magnitude as i64)
    }
}

/// Reads a JSON value as a `Bool`.
#[no_mangle]
pub unsafe extern "C" fn __souther_read_bool(value: u32) -> u32 {
    match json::__souther_json_tag(value) {
        json::TAG_TRUE => __souther_bool(1),
        json::TAG_FALSE => __souther_bool(0),
        other => abort(REASON_NOT_WHAT_WAS_DECLARED, 0, other as u64, TAG_BOOL as u64),
    }
}

/// Reads a JSON value as a `String`.
#[no_mangle]
pub unsafe extern "C" fn __souther_read_string(value: u32) -> u32 {
    expect(value, json::TAG_STRING);
    __souther_string(
        json::__souther_json_bytes(value),
        json::__souther_json_length(value),
    )
}

/// Writes a value as the JSON a caller reads, answering the pointer and the length packed.
#[no_mangle]
pub unsafe extern "C" fn __souther_write(cell: u32) -> u64 {
    match core::ptr::read_unaligned(cell as usize as *const u32) {
        TAG_INT => json::__souther_json_write_int(__souther_int_value(cell)),
        TAG_BOOL => json::__souther_json_write_bool(__souther_bool_value(cell)),
        TAG_STRING => json::__souther_json_write_string(
            __souther_string_bytes(cell),
            __souther_string_length(cell),
        ),
        TAG_UNIT => {
            let out = alloc(4);
            core::ptr::copy_nonoverlapping(b"null".as_ptr(), out as *mut u8, 4);
            json::packed(out, 4)
        }
        other => abort(REASON_NOT_WHAT_WAS_DECLARED, 0, other as u64, 0),
    }
}

unsafe fn expect(value: u32, tag: u32) {
    let held = json::__souther_json_tag(value);
    if held != tag {
        abort(REASON_NOT_WHAT_WAS_DECLARED, 0, held as u64, tag as u64);
    }
}

unsafe fn header(tag: u32, length: u32) -> u32 {
    let cell = alloc(HEADER as u32);
    core::ptr::write_unaligned(cell as usize as *mut u32, tag);
    core::ptr::write_unaligned((cell as usize + 4) as *mut u32, length);
    cell
}
