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
//! A reader that cannot read what it was handed records an issue and answers nothing. Nothing is a
//! null pointer here, and no body ever reads one: the generated code asks whether any issue was
//! found before it runs, so what a refused place leaves behind is never stood in for.

use crate::issues::{self, CODE_INVALID_SIZE, CODE_OUT_OF_RANGE, CODE_TYPE_MISMATCH};
use crate::json;
use crate::{abort, alloc, REASON_NOT_A_VALUE};

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

/// Checks that what a caller handed in is a call of a behavior taking this many parameters.
///
/// A call's arguments are one JSON array, in the order the behavior declares its parameters.
/// Something else there is bad input rather than a fault, so it is recorded at the root and the
/// arguments are not read: there is nowhere to read them from.
#[no_mangle]
pub unsafe extern "C" fn __souther_check_arguments(document: u32, expected: u32) {
    let tag = json::__souther_json_tag(document);
    if tag != json::TAG_ARRAY {
        issues::issue(CODE_TYPE_MISMATCH, 0, 0, kind(tag), b"arguments");
        return;
    }
    let held = json::__souther_json_length(document);
    if held != expected {
        issues::issue_of(CODE_INVALID_SIZE, 0, 0, decimal(held), decimal(expected));
    }
}

/// The argument at a position of what a caller handed in.
#[no_mangle]
pub unsafe extern "C" fn __souther_argument(document: u32, index: u32) -> u32 {
    json::__souther_json_element(document, index)
}

/// Reads a JSON value as an `Int`.
///
/// A whole number and nothing else. Digits after a point are a `Decimal`'s and not something to
/// round away here, and a number outside what sixty-four bits hold is not an `Int` at all.
#[no_mangle]
pub unsafe extern "C" fn __souther_read_int(value: u32, path: u32, path_length: u32) -> u32 {
    let tag = json::__souther_json_tag(value);
    if tag != json::TAG_NUMBER {
        issues::issue(CODE_TYPE_MISMATCH, path, path_length, kind(tag), b"Int");
        return 0;
    }
    let bytes = json::__souther_json_bytes(value) as usize;
    let length = json::__souther_json_length(value) as usize;
    let negative = core::ptr::read(bytes as *const u8) == b'-';
    let mut at = usize::from(negative);
    let mut magnitude: u128 = 0;
    while at < length {
        let digit = core::ptr::read((bytes + at) as *const u8);
        if !digit.is_ascii_digit() {
            // A point or an exponent means the document wrote an amount, not a whole number.
            issues::issue(CODE_TYPE_MISMATCH, path, path_length, b"number", b"Int");
            return 0;
        }
        magnitude = magnitude * 10 + (digit - b'0') as u128;
        if magnitude > 1u128 << 63 {
            issues::issue(CODE_OUT_OF_RANGE, path, path_length, b"number", b"Int");
            return 0;
        }
        at += 1;
    }
    let limit = if negative { 1u128 << 63 } else { i64::MAX as u128 };
    if magnitude > limit {
        issues::issue(CODE_OUT_OF_RANGE, path, path_length, b"number", b"Int");
        return 0;
    }
    if negative {
        __souther_int((magnitude as i128).wrapping_neg() as i64)
    } else {
        __souther_int(magnitude as i64)
    }
}

/// Reads a JSON value as a `Bool`.
#[no_mangle]
pub unsafe extern "C" fn __souther_read_bool(value: u32, path: u32, path_length: u32) -> u32 {
    match json::__souther_json_tag(value) {
        json::TAG_TRUE => __souther_bool(1),
        json::TAG_FALSE => __souther_bool(0),
        other => {
            issues::issue(CODE_TYPE_MISMATCH, path, path_length, kind(other), b"Bool");
            0
        }
    }
}

/// Reads a JSON value as a `String`.
#[no_mangle]
pub unsafe extern "C" fn __souther_read_string(value: u32, path: u32, path_length: u32) -> u32 {
    let tag = json::__souther_json_tag(value);
    if tag != json::TAG_STRING {
        issues::issue(CODE_TYPE_MISMATCH, path, path_length, kind(tag), b"String");
        return 0;
    }
    __souther_string(
        json::__souther_json_bytes(value),
        json::__souther_json_length(value),
    )
}

/// Writes a value as the answer a caller reads, answering the pointer and the length packed.
#[no_mangle]
pub unsafe extern "C" fn __souther_write(cell: u32) -> u64 {
    let out = crate::next_free();
    write(b"{\"value\":");
    match core::ptr::read_unaligned(cell as usize as *const u32) {
        TAG_INT => {
            json::__souther_json_write_int(__souther_int_value(cell));
        }
        TAG_BOOL => {
            json::__souther_json_write_bool(__souther_bool_value(cell));
        }
        TAG_STRING => {
            json::__souther_json_write_string(
                __souther_string_bytes(cell),
                __souther_string_length(cell),
            );
        }
        TAG_UNIT => write(b"null"),
        // Nothing a decoder was handed reaches here: a value whose tag this does not know is one
        // the emitter made, so it is this compiler that is wrong rather than the input.
        other => abort(REASON_NOT_A_VALUE, 0, other as u64, cell as u64),
    }
    write(b"}");
    json::packed(out, crate::next_free() - out)
}

unsafe fn write(bytes: &[u8]) {
    let at = alloc(bytes.len() as u32);
    core::ptr::copy_nonoverlapping(bytes.as_ptr(), at as *mut u8, bytes.len());
}

/// What a JSON value is, in the words an issue reports it with.
fn kind(tag: u32) -> &'static [u8] {
    match tag {
        json::TAG_NULL => b"null",
        json::TAG_FALSE | json::TAG_TRUE => b"boolean",
        json::TAG_NUMBER => b"number",
        json::TAG_STRING => b"string",
        json::TAG_ARRAY => b"array",
        _ => b"object",
    }
}

/// A count as digits, written into the arena.
///
/// Into the arena and not into a buffer this function keeps, because an issue names two of these
/// at once — what was there and what was wanted — and one buffer would hand back the same bytes
/// for both, so the second question would answer the first.
unsafe fn decimal(value: u32) -> (u32, u32) {
    let mut digits = [0u8; 10];
    let mut written = 0;
    let mut rest = value;
    if rest == 0 {
        digits[0] = b'0';
        written = 1;
    }
    while rest > 0 {
        digits[written] = b'0' + (rest % 10) as u8;
        written += 1;
        rest /= 10;
    }
    let at = alloc(written as u32);
    for i in 0..written {
        core::ptr::write((at as usize + i) as *mut u8, digits[written - 1 - i]);
    }
    (at, written as u32)
}

unsafe fn header(tag: u32, length: u32) -> u32 {
    let cell = alloc(HEADER as u32);
    core::ptr::write_unaligned(cell as usize as *mut u32, tag);
    core::ptr::write_unaligned((cell as usize + 4) as *mut u32, length);
    cell
}
