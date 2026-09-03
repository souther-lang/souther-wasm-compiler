//! A Souther value while a call is running, and how one is read out of JSON and written back.
//!
//! # The cell
//!
//! Every value is a cell in the arena, whatever it is:
//!
//! ```text
//! +0  u32 tag
//! +4  u32 length for a String, the descriptor for a record or a unit, nothing otherwise
//! +8  payload
//! ```
//!
//! Boxed even where it need not be. An `Int` in a local would be an `i64` and nothing else, but the
//! same `Int` inside a list, a map or an option has to be reachable by a pointer like everything
//! else there, and a representation that changed at the edge of a container would put a conversion
//! at every one of those edges. So the box comes first and unboxing a local is something to add
//! against a measurement, not before one.
//!
//! # Reading and writing
//!
//! Both are walks of a declared type against a document, in step. What a place holds is decided by
//! the declaration: reading asks the JSON to be what was declared rather than asking the JSON what
//! it is, and writing puts a tag on a value exactly where the place it fills is a sum.
//!
//! A reader that cannot read what it was handed records an issue and answers nothing. Nothing is a
//! null pointer, and no body ever reads one: the generated code asks whether anything was refused
//! before it runs, so what a refused place leaves behind is never stood in for.

use crate::descriptor::{
    self, KIND_BOOL, KIND_INT, KIND_PRODUCT, KIND_STRING, KIND_SUM, KIND_UNIT,
};
use crate::issues::{
    self, CODE_INVALID_SIZE, CODE_MISSING_FIELD, CODE_NOT_ALLOWED, CODE_OUT_OF_RANGE,
    CODE_TYPE_MISMATCH,
};
use crate::json;
use crate::{abort, alloc, REASON_NOT_A_VALUE};

/// The one value a type with a single value has. `+4` is which type.
pub const TAG_UNIT: u32 = 0;
/// An `Int`, whose payload is sixty-four bits.
pub const TAG_INT: u32 = 1;
/// A `Bool`, whose payload is one or zero.
pub const TAG_BOOL: u32 = 2;
/// A `String`, whose payload is its UTF-8 bytes and whose `+4` is how many.
pub const TAG_STRING: u32 = 3;
/// A value written as fields. `+4` is which shape, and the payload is one pointer per field.
pub const TAG_RECORD: u32 = 4;

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

/// The one value of a type that has one.
#[no_mangle]
pub unsafe extern "C" fn __souther_unit(descriptor: u32) -> u32 {
    header(TAG_UNIT, descriptor)
}

/// A cell of a declared shape, with room for its fields and nothing in them yet.
///
/// Nothing in them because a field is filled once it has been read, and a field that was refused
/// is left as it started. No body runs while anything was refused, so what a hole holds is never
/// asked.
#[no_mangle]
pub unsafe extern "C" fn __souther_record(descriptor: u32) -> u32 {
    let cell = header(TAG_RECORD, descriptor);
    let _ = alloc(4 * descriptor::arity(descriptor));
    cell
}

/// Puts a value in one of a record's fields.
#[no_mangle]
pub unsafe extern "C" fn __souther_record_set(cell: u32, index: u32, value: u32) {
    core::ptr::write_unaligned((cell as usize + HEADER + 4 * index as usize) as *mut u32, value);
}

/// The value in one of a record's fields.
#[no_mangle]
pub unsafe extern "C" fn __souther_record_get(cell: u32, index: u32) -> u32 {
    core::ptr::read_unaligned((cell as usize + HEADER + 4 * index as usize) as *const u32)
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
        issues::issue(CODE_TYPE_MISMATCH, 0, 0, kind_of(tag), b"arguments");
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

/// Reads a JSON value as a value of a declared type.
///
/// Answers nothing where it could not, having said why and where. Every place is read, including
/// the ones after a place that was refused: a caller told about one field at a time is made to ask
/// as many times as its document had mistakes.
#[no_mangle]
pub unsafe extern "C" fn __souther_read(
    value: u32,
    descriptor: u32,
    path: u32,
    path_length: u32,
) -> u32 {
    if value == 0 {
        return 0;
    }
    match descriptor::kind(descriptor) {
        KIND_INT => integer(value, path, path_length),
        KIND_BOOL => boolean(value, path, path_length),
        KIND_STRING => text(value, path, path_length),
        KIND_UNIT => unit(value, descriptor, path, path_length),
        KIND_PRODUCT => product(value, descriptor, path, path_length),
        KIND_SUM => sum(value, descriptor, path, path_length),
        // Nothing a caller wrote reaches this: a descriptor is placed by the emitter, so a kind
        // no one knows means this compiler wrote it rather than that a document said something.
        other => abort(REASON_NOT_A_VALUE, descriptor, other as u64, 0),
    }
}

unsafe fn integer(value: u32, path: u32, path_length: u32) -> u32 {
    let tag = json::__souther_json_tag(value);
    if tag != json::TAG_NUMBER {
        issues::issue(CODE_TYPE_MISMATCH, path, path_length, kind_of(tag), b"Int");
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

unsafe fn boolean(value: u32, path: u32, path_length: u32) -> u32 {
    match json::__souther_json_tag(value) {
        json::TAG_TRUE => __souther_bool(1),
        json::TAG_FALSE => __souther_bool(0),
        other => {
            issues::issue(CODE_TYPE_MISMATCH, path, path_length, kind_of(other), b"Bool");
            0
        }
    }
}

unsafe fn text(value: u32, path: u32, path_length: u32) -> u32 {
    let tag = json::__souther_json_tag(value);
    if tag != json::TAG_STRING {
        issues::issue(CODE_TYPE_MISMATCH, path, path_length, kind_of(tag), b"String");
        return 0;
    }
    __souther_string(
        json::__souther_json_bytes(value),
        json::__souther_json_length(value),
    )
}

/// A type with one value is written as an empty object: there is nothing to say about which one it
/// is, and a document that says something is saying something the type has no room for.
unsafe fn unit(value: u32, descriptor: u32, path: u32, path_length: u32) -> u32 {
    let tag = json::__souther_json_tag(value);
    if tag != json::TAG_OBJECT {
        issues::issue(CODE_TYPE_MISMATCH, path, path_length, kind_of(tag), b"an object");
        return 0;
    }
    __souther_unit(descriptor)
}

unsafe fn product(value: u32, descriptor: u32, path: u32, path_length: u32) -> u32 {
    let tag = json::__souther_json_tag(value);
    if tag != json::TAG_OBJECT {
        issues::issue(CODE_TYPE_MISMATCH, path, path_length, kind_of(tag), b"an object");
        return 0;
    }
    let cell = __souther_record(descriptor);
    let mut whole = true;
    for i in 0..descriptor::arity(descriptor) {
        let (field, field_length) = descriptor::name(descriptor, i);
        let (at, at_length) = below(path, path_length, field, field_length);
        let written = entry(value, field, field_length);
        if written == 0 {
            issues::issue(CODE_MISSING_FIELD, at, at_length, b"nothing", b"a field");
            whole = false;
            continue;
        }
        let read = __souther_read(written, descriptor::member(descriptor, i), at, at_length);
        if read == 0 {
            whole = false;
        }
        __souther_record_set(cell, i, read);
    }
    if whole {
        cell
    } else {
        0
    }
}

/// A value of a sum is written as its case, with the case's name under `type`.
unsafe fn sum(value: u32, descriptor: u32, path: u32, path_length: u32) -> u32 {
    let tag = json::__souther_json_tag(value);
    if tag != json::TAG_OBJECT {
        issues::issue(CODE_TYPE_MISMATCH, path, path_length, kind_of(tag), b"an object");
        return 0;
    }
    let written = entry(value, DISCRIMINATOR.as_ptr() as u32, DISCRIMINATOR.len() as u32);
    if written == 0 {
        let (at, at_length) = below(
            path,
            path_length,
            DISCRIMINATOR.as_ptr() as u32,
            DISCRIMINATOR.len() as u32,
        );
        issues::issue(CODE_MISSING_FIELD, at, at_length, b"nothing", b"a case");
        return 0;
    }
    if json::__souther_json_tag(written) != json::TAG_STRING {
        let (at, at_length) = below(
            path,
            path_length,
            DISCRIMINATOR.as_ptr() as u32,
            DISCRIMINATOR.len() as u32,
        );
        issues::issue(
            CODE_TYPE_MISMATCH,
            at,
            at_length,
            kind_of(json::__souther_json_tag(written)),
            b"a case",
        );
        return 0;
    }
    let held = json::__souther_json_bytes(written);
    let held_length = json::__souther_json_length(written);
    for i in 0..descriptor::arity(descriptor) {
        let (case, case_length) = descriptor::name(descriptor, i);
        if same(case, case_length, held, held_length) {
            return __souther_read(value, descriptor::member(descriptor, i), path, path_length);
        }
    }
    let (at, at_length) = below(
        path,
        path_length,
        DISCRIMINATOR.as_ptr() as u32,
        DISCRIMINATOR.len() as u32,
    );
    issues::issue_of(
        CODE_NOT_ALLOWED,
        at,
        at_length,
        (held, held_length),
        (b"a case".as_ptr() as u32, 6),
    );
    0
}

/// The key a sum's case is named under. ADR-0004's derived discriminator, which is what the JVM
/// backend's derived codec reads and writes.
const DISCRIMINATOR: &[u8] = b"type";

/// What an object wrote at a key, or nothing.
unsafe fn entry(object: u32, name: u32, name_length: u32) -> u32 {
    for i in 0..json::__souther_json_length(object) {
        let key = json::__souther_json_key(object, i);
        if same(
            json::__souther_json_bytes(key),
            json::__souther_json_length(key),
            name,
            name_length,
        ) {
            return json::__souther_json_value(object, i);
        }
    }
    0
}

/// The JSON pointer of a place inside another, written into the arena.
unsafe fn below(path: u32, path_length: u32, step: u32, step_length: u32) -> (u32, u32) {
    let total = path_length + 1 + step_length;
    let at = alloc(total);
    core::ptr::copy_nonoverlapping(path as *const u8, at as *mut u8, path_length as usize);
    core::ptr::write((at + path_length) as *mut u8, b'/');
    core::ptr::copy_nonoverlapping(
        step as *const u8,
        (at + path_length + 1) as *mut u8,
        step_length as usize,
    );
    (at, total)
}

/// Writes a value as the answer a caller reads, answering the pointer and the length packed.
///
/// Against the declared type, not against the value alone: the tag saying which case a value is
/// belongs where a sum was declared and nowhere else, and what a cell holds cannot say whether the
/// place it fills was declared as the sum or as the case.
#[no_mangle]
pub unsafe extern "C" fn __souther_write(cell: u32, descriptor: u32) -> u64 {
    let out = crate::next_free();
    write(b"{\"value\":");
    written(cell, descriptor);
    write(b"}");
    json::packed(out, crate::next_free() - out)
}

unsafe fn written(cell: u32, descriptor: u32) {
    match descriptor::kind(descriptor) {
        KIND_INT => {
            json::__souther_json_write_int(__souther_int_value(cell));
        }
        KIND_BOOL => {
            json::__souther_json_write_bool(__souther_bool_value(cell));
        }
        KIND_STRING => {
            json::__souther_json_write_string(
                __souther_string_bytes(cell),
                __souther_string_length(cell),
            );
        }
        KIND_UNIT => write(b"{}"),
        KIND_PRODUCT => fields(cell, descriptor, false),
        KIND_SUM => tagged(cell, descriptor),
        other => abort(REASON_NOT_A_VALUE, descriptor, other as u64, cell as u64),
    }
}

/// A value of a sum, under the tag of the case it is.
///
/// Which case is asked of the value: a cell holds the descriptor of the type it was made as, and
/// that is one of the cases the place's own type offers.
unsafe fn tagged(cell: u32, descriptor: u32) {
    let held = core::ptr::read_unaligned((cell as usize + 4) as *const u32);
    for i in 0..descriptor::arity(descriptor) {
        let case = descriptor::member(descriptor, i);
        if case == held {
            let (tag, tag_length) = descriptor::name(descriptor, i);
            write(b"{\"");
            write(DISCRIMINATOR);
            write(b"\":");
            json::__souther_json_write_string(tag, tag_length);
            if descriptor::kind(case) == KIND_PRODUCT {
                fields(cell, case, true);
            } else {
                write(b"}");
            }
            return;
        }
    }
    abort(REASON_NOT_A_VALUE, descriptor, held as u64, cell as u64);
}

/// A record's fields, either as the whole object or as the rest of one already opened by a tag.
unsafe fn fields(cell: u32, descriptor: u32, opened: bool) {
    if !opened {
        write(b"{");
    }
    for i in 0..descriptor::arity(descriptor) {
        if opened || i > 0 {
            write(b",");
        }
        let (field, field_length) = descriptor::name(descriptor, i);
        json::__souther_json_write_string(field, field_length);
        write(b":");
        written(__souther_record_get(cell, i), descriptor::member(descriptor, i));
    }
    write(b"}");
}

unsafe fn write(bytes: &[u8]) {
    let at = alloc(bytes.len() as u32);
    core::ptr::copy_nonoverlapping(bytes.as_ptr(), at as *mut u8, bytes.len());
}

unsafe fn same(left: u32, left_length: u32, right: u32, right_length: u32) -> bool {
    if left_length != right_length {
        return false;
    }
    for i in 0..left_length as usize {
        if core::ptr::read((left as usize + i) as *const u8)
            != core::ptr::read((right as usize + i) as *const u8)
        {
            return false;
        }
    }
    true
}

/// What a JSON value is, in the words an issue reports it with.
fn kind_of(tag: u32) -> &'static [u8] {
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

unsafe fn header(tag: u32, second: u32) -> u32 {
    let cell = alloc(HEADER as u32);
    core::ptr::write_unaligned(cell as usize as *mut u32, tag);
    core::ptr::write_unaligned((cell as usize + 4) as *mut u32, second);
    cell
}
