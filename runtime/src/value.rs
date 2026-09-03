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
    self, KIND_BOOL, KIND_ENUMERATION, KIND_INT, KIND_LIST, KIND_MAP, KIND_OPTION, KIND_PRODUCT,
    KIND_SET, KIND_STRING, KIND_SUM, KIND_UNIT,
};
use crate::order;
use crate::issues::{
    self, CODE_INVALID_SIZE, CODE_INVARIANT_VIOLATION, CODE_MISSING_FIELD, CODE_NOT_ALLOWED,
    CODE_OUT_OF_RANGE, CODE_TYPE_MISMATCH,
};
use crate::json;
use crate::{abort, alloc, REASON_DIVISION_BY_ZERO, REASON_INT_OVERFLOW, REASON_NOT_A_VALUE};

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
/// A list. `+8` is how many elements, and the pointers follow.
pub const TAG_LIST: u32 = 5;
/// An option holding something, whose payload is the pointer to it.
pub const TAG_SOME: u32 = 6;
/// An option holding nothing.
pub const TAG_NONE: u32 = 7;
/// A map. `+8` is how many entries, and a key pointer and a value pointer follow per entry.
pub const TAG_MAP: u32 = 8;
/// A block written where a value goes. `+4` is the table slot its body sits in and `+8` is what it
/// was written among — the values it reads that were bound outside it.
pub const TAG_CLOSURE: u32 = 9;
/// A list still being grown. Not a list: what it holds is followed by room it does not, so a
/// reader taking it for one would read past what is there.
pub const TAG_BUILDER: u32 = 10;

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

/// A list of that many elements, with nothing in them yet.
#[no_mangle]
pub unsafe extern "C" fn __souther_list(descriptor: u32, length: u32) -> u32 {
    let cell = header(TAG_LIST, descriptor);
    let _ = alloc(4 + 4 * length);
    core::ptr::write_unaligned((cell as usize + HEADER) as *mut u32, length);
    cell
}

/// Puts a value at a position of a list.
#[no_mangle]
pub unsafe extern "C" fn __souther_list_set(cell: u32, index: u32, value: u32) {
    core::ptr::write_unaligned(
        (cell as usize + HEADER + 4 + 4 * index as usize) as *mut u32,
        value,
    );
}

/// How many elements a list holds.
#[no_mangle]
pub unsafe extern "C" fn __souther_list_length(cell: u32) -> u32 {
    core::ptr::read_unaligned((cell as usize + HEADER) as *const u32)
}

/// The value at a position of a list.
#[no_mangle]
pub unsafe extern "C" fn __souther_list_get(cell: u32, index: u32) -> u32 {
    core::ptr::read_unaligned((cell as usize + HEADER + 4 + 4 * index as usize) as *const u32)
}

/// Whether a value is one of the type a descriptor describes.
///
/// A value of a declared type holds the descriptor it was made as, and is that type where the two
/// are the same one. A scalar holds no descriptor — an `Int` is an `Int` and there is only one —
/// so it is that type where its tag says so.
#[no_mangle]
pub unsafe extern "C" fn __souther_is(cell: u32, descriptor: u32) -> u32 {
    let tag = core::ptr::read_unaligned(cell as usize as *const u32);
    u32::from(match descriptor::kind(descriptor) {
        KIND_INT => tag == TAG_INT,
        KIND_BOOL => tag == TAG_BOOL,
        KIND_STRING => tag == TAG_STRING,
        KIND_LIST | KIND_SET => tag == TAG_LIST,
        KIND_MAP => tag == TAG_MAP,
        KIND_OPTION => tag == TAG_SOME || tag == TAG_NONE,
        _ => core::ptr::read_unaligned((cell as usize + 4) as *const u32) == descriptor,
    })
}

/// Whether an option holds something.
#[no_mangle]
pub unsafe extern "C" fn __souther_is_some(cell: u32) -> u32 {
    u32::from(core::ptr::read_unaligned(cell as usize as *const u32) == TAG_SOME)
}

/// What an option holds. Asked only where it holds something.
#[no_mangle]
pub unsafe extern "C" fn __souther_held(cell: u32) -> u32 {
    core::ptr::read_unaligned((cell as usize + HEADER) as *const u32)
}

/// The `+` operator on `Int`. Leaving the range is a model bug rather than a value, so it ends the
/// call: nothing an `Int` can hold is the answer, and a wrapped one would be a different number
/// quietly standing where the right one was.
#[no_mangle]
pub unsafe extern "C" fn __souther_add(left: u32, right: u32) -> u32 {
    let (a, b) = (__souther_int_value(left), __souther_int_value(right));
    match a.checked_add(b) {
        Some(sum) => __souther_int(sum),
        None => abort(REASON_INT_OVERFLOW, 0, a as u64, b as u64),
    }
}

/// The unary `-` on `Int`. Only the sign moves, and the one number that has no opposite an `Int`
/// holds ends the call rather than coming back as itself.
#[no_mangle]
pub unsafe extern "C" fn __souther_negate(cell: u32) -> u32 {
    let held = __souther_int_value(cell);
    match held.checked_neg() {
        Some(opposite) => __souther_int(opposite),
        None => abort(REASON_INT_OVERFLOW, 0, held as u64, 0),
    }
}

/// The `-` operator on `Int`.
#[no_mangle]
pub unsafe extern "C" fn __souther_subtract(left: u32, right: u32) -> u32 {
    let (a, b) = (__souther_int_value(left), __souther_int_value(right));
    match a.checked_sub(b) {
        Some(difference) => __souther_int(difference),
        None => abort(REASON_INT_OVERFLOW, 0, a as u64, b as u64),
    }
}

/// The `*` operator on `Int`.
#[no_mangle]
pub unsafe extern "C" fn __souther_multiply(left: u32, right: u32) -> u32 {
    let (a, b) = (__souther_int_value(left), __souther_int_value(right));
    match a.checked_mul(b) {
        Some(product) => __souther_int(product),
        None => abort(REASON_INT_OVERFLOW, 0, a as u64, b as u64),
    }
}

/// The `/` operator on `Int`: truncating, and ending the call on a zero divisor.
///
/// A zero divisor is a model bug here rather than a case. Code that means it as a case asks
/// `Int.divide`, whose type says so.
#[no_mangle]
pub unsafe extern "C" fn __souther_divide(left: u32, right: u32) -> u32 {
    let (a, b) = (__souther_int_value(left), __souther_int_value(right));
    if b == 0 {
        abort(REASON_DIVISION_BY_ZERO, 0, a as u64, 0);
    }
    match a.checked_div(b) {
        Some(quotient) => __souther_int(quotient),
        None => abort(REASON_INT_OVERFLOW, 0, a as u64, b as u64),
    }
}

/// Where one value stands relative to another of its type, as a whole number.
///
/// What a comparison in a body asks, which is not what a set is written in the order of: a set of
/// alternatives places its own in the order its declaration writes them and is written as their
/// names.
#[no_mangle]
pub unsafe extern "C" fn __souther_compare(left: u32, right: u32, descriptor: u32) -> i32 {
    order::ranked(left, right, descriptor)
}

/// The `++` operator on `String`.
#[no_mangle]
pub unsafe extern "C" fn __souther_concat(left: u32, right: u32) -> u32 {
    let (a, a_length) = (__souther_string_bytes(left), __souther_string_length(left));
    let (b, b_length) = (__souther_string_bytes(right), __souther_string_length(right));
    let cell = header(TAG_STRING, a_length + b_length);
    let _ = alloc(a_length + b_length);
    core::ptr::copy_nonoverlapping(a as *const u8, (cell as usize + HEADER) as *mut u8, a_length as usize);
    core::ptr::copy_nonoverlapping(
        b as *const u8,
        (cell as usize + HEADER + a_length as usize) as *mut u8,
        b_length as usize,
    );
    cell
}

/// A map of that many entries, with nothing in them yet.
#[no_mangle]
pub unsafe extern "C" fn __souther_map(descriptor: u32, entries: u32) -> u32 {
    let cell = header(TAG_MAP, descriptor);
    let _ = alloc(4 + 8 * entries);
    core::ptr::write_unaligned((cell as usize + HEADER) as *mut u32, entries);
    cell
}

/// How many entries a map holds.
#[no_mangle]
pub unsafe extern "C" fn __souther_map_length(cell: u32) -> u32 {
    core::ptr::read_unaligned((cell as usize + HEADER) as *const u32)
}

/// The key of one of a map's entries.
#[no_mangle]
pub unsafe extern "C" fn __souther_map_key(cell: u32, index: u32) -> u32 {
    core::ptr::read_unaligned((cell as usize + HEADER + 4 + 8 * index as usize) as *const u32)
}

/// The value of one of a map's entries.
#[no_mangle]
pub unsafe extern "C" fn __souther_map_value(cell: u32, index: u32) -> u32 {
    core::ptr::read_unaligned((cell as usize + HEADER + 8 + 8 * index as usize) as *const u32)
}

/// Puts an entry at a position of a map.
#[no_mangle]
pub unsafe extern "C" fn __souther_map_set(cell: u32, index: u32, key: u32, value: u32) {
    core::ptr::write_unaligned((cell as usize + HEADER + 4 + 8 * index as usize) as *mut u32, key);
    core::ptr::write_unaligned((cell as usize + HEADER + 8 + 8 * index as usize) as *mut u32, value);
}

/// Shortens a map to the entries it kept.
unsafe fn map_of_length(cell: u32, entries: u32) {
    core::ptr::write_unaligned((cell as usize + HEADER) as *mut u32, entries);
}

/// A block as a value: where its body is, and what it reads from around it.
#[no_mangle]
pub unsafe extern "C" fn __souther_closure(slot: u32, captured: u32) -> u32 {
    let cell = header(TAG_CLOSURE, slot);
    let _ = alloc(4);
    core::ptr::write_unaligned((cell as usize + HEADER) as *mut u32, captured);
    cell
}

/// The table slot a closure's body sits in.
#[no_mangle]
pub unsafe extern "C" fn __souther_closure_slot(cell: u32) -> u32 {
    core::ptr::read_unaligned((cell as usize + 4) as *const u32)
}

/// What a closure was written among.
#[no_mangle]
pub unsafe extern "C" fn __souther_closure_captured(cell: u32) -> u32 {
    core::ptr::read_unaligned((cell as usize + HEADER) as *const u32)
}

/// A list that grows, for a walk that does not know how long its answer will be.
///
/// The same cell a finished list is, with room past what it holds. Growing past that room copies
/// into a longer one, which is why what a walk answers is asked for at the end rather than being
/// the cell it started with.
#[no_mangle]
pub unsafe extern "C" fn __souther_builder(descriptor: u32) -> u32 {
    let cell = header(TAG_BUILDER, descriptor);
    let _ = alloc(4 + 4 * INITIAL_ROOM);
    core::ptr::write_unaligned((cell as usize + HEADER) as *mut u32, 0);
    core::ptr::write_unaligned((cell as usize + HEADER + 4) as *mut u32, INITIAL_ROOM);
    cell
}

/// How many places a builder starts with. One is enough to be right and slow; this is enough to be
/// right and not slow for a walk over a document a caller wrote.
const INITIAL_ROOM: u32 = 4;

/// Adds everything a list holds to the end of a builder, answering the builder that holds it.
///
/// A list and not one value, because what a step writes is `acc ++ [x]` — the walk grows by
/// whatever the step wrote there, which is a list of none, one or more.
#[no_mangle]
pub unsafe extern "C" fn __souther_grow(builder: u32, added: u32) -> u32 {
    let mut held = builder;
    for i in 0..__souther_list_length(added) {
        held = grown(held, __souther_list_get(added, i));
    }
    held
}

/// Adds one value to the end of a builder.
unsafe fn grown(builder: u32, value: u32) -> u32 {
    let held = core::ptr::read_unaligned((builder as usize + HEADER) as *const u32);
    let room = core::ptr::read_unaligned((builder as usize + HEADER + 4) as *const u32);
    if held < room {
        core::ptr::write_unaligned(
            (builder as usize + HEADER + 8 + 4 * held as usize) as *mut u32,
            value,
        );
        core::ptr::write_unaligned((builder as usize + HEADER) as *mut u32, held + 1);
        return builder;
    }
    let descriptor = core::ptr::read_unaligned((builder as usize + 4) as *const u32);
    let wider = header(TAG_BUILDER, descriptor);
    let _ = alloc(4 + 4 * (room * 2 + 1));
    core::ptr::write_unaligned((wider as usize + HEADER) as *mut u32, held + 1);
    core::ptr::write_unaligned((wider as usize + HEADER + 4) as *mut u32, room * 2 + 1);
    for i in 0..held {
        core::ptr::write_unaligned(
            (wider as usize + HEADER + 8 + 4 * i as usize) as *mut u32,
            core::ptr::read_unaligned(
                (builder as usize + HEADER + 8 + 4 * i as usize) as *const u32,
            ),
        );
    }
    core::ptr::write_unaligned(
        (wider as usize + HEADER + 8 + 4 * held as usize) as *mut u32,
        value,
    );
    wider
}

/// The list a builder has grown, with nothing past what it holds.
#[no_mangle]
pub unsafe extern "C" fn __souther_sealed(builder: u32) -> u32 {
    let held = core::ptr::read_unaligned((builder as usize + HEADER) as *const u32);
    let descriptor = core::ptr::read_unaligned((builder as usize + 4) as *const u32);
    let out = __souther_list(descriptor, held);
    for i in 0..held {
        __souther_list_set(
            out,
            i,
            core::ptr::read_unaligned(
                (builder as usize + HEADER + 8 + 4 * i as usize) as *const u32,
            ),
        );
    }
    out
}

/// An option holding a value.
#[no_mangle]
pub unsafe extern "C" fn __souther_some(value: u32) -> u32 {
    let cell = header(TAG_SOME, 0);
    let _ = alloc(4);
    core::ptr::write_unaligned((cell as usize + HEADER) as *mut u32, value);
    cell
}

/// An option holding nothing.
#[no_mangle]
pub unsafe extern "C" fn __souther_none() -> u32 {
    header(TAG_NONE, 0)
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
        KIND_ENUMERATION => enumeration(value, descriptor, path, path_length),
        KIND_LIST => list(value, descriptor, path, path_length, false),
        KIND_SET => list(value, descriptor, path, path_length, true),
        KIND_MAP => map(value, descriptor, path, path_length),
        KIND_OPTION => option(value, descriptor, path, path_length),
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
        let (at, at_length) = below(path, path_length, (field, field_length));
        let member = descriptor::member(descriptor, i);
        let written = entry(value, field, field_length);
        if written == 0 {
            // An optional field has a key to be missing, and its being missing is what absence is
            // written as there — not `null`, which is what absence is where there is no key.
            if descriptor::kind(member) == KIND_OPTION {
                __souther_record_set(cell, i, __souther_none());
                continue;
            }
            issues::issue(CODE_MISSING_FIELD, at, at_length, b"nothing", b"a field");
            whole = false;
            continue;
        }
        let read = __souther_read(written, member, at, at_length);
        if read == 0 {
            whole = false;
        }
        __souther_record_set(cell, i, read);
    }
    if !whole {
        return 0;
    }
    // What must hold of a value is checked where the value is made, whether that is a body or the
    // boundary. Here it is the boundary, so a violation is what a caller wrote rather than a fault
    // and it is answered as an issue.
    let clause = __souther_check_invariants(cell, descriptor);
    if clause >= 0 {
        issues::issue_of(
            CODE_INVARIANT_VIOLATION,
            path,
            path_length,
            decimal(clause as u32),
            descriptor::own_name(descriptor),
        );
        return 0;
    }
    cell
}

/// Which of a type's invariants a value breaks, or minus one where it breaks none.
///
/// The check is generated: what must hold of a value is written in Souther, so what runs it is a
/// body this runtime knows nothing about, reached through the module's table.
#[no_mangle]
pub unsafe extern "C" fn __souther_check_invariants(cell: u32, descriptor: u32) -> i32 {
    if descriptor::kind(descriptor) != KIND_PRODUCT {
        return -1;
    }
    let slot = descriptor::invariant(descriptor);
    if slot == 0 {
        return -1;
    }
    crate::__souther_call_slot(slot, cell) as i32
}

/// A list is written as an array, its elements in the order it holds them; a set is one too, in
/// the order its members are written in, with what was written twice held once.
unsafe fn list(value: u32, descriptor: u32, path: u32, path_length: u32, unique: bool) -> u32 {
    let tag = json::__souther_json_tag(value);
    if tag != json::TAG_ARRAY {
        issues::issue(CODE_TYPE_MISMATCH, path, path_length, kind_of(tag), b"an array");
        return 0;
    }
    let held = json::__souther_json_length(value);
    let cell = __souther_list(descriptor, held);
    let element = descriptor::member(descriptor, 0);
    let mut whole = true;
    for i in 0..held {
        let (at, at_length) = below(path, path_length, decimal(i));
        let read = __souther_read(json::__souther_json_element(value, i), element, at, at_length);
        if read == 0 {
            whole = false;
        }
        __souther_list_set(cell, i, read);
    }
    if !whole {
        return 0;
    }
    if unique {
        return sorted_and_deduplicated(cell, descriptor);
    }
    cell
}

/// The members of a set, in the order they are written in, each held once.
///
/// Sorted here rather than on the way out because what a set is does not depend on how it was
/// written: two documents listing the same members are one set, and a set that only settled its
/// order at the boundary would compare as two.
unsafe fn sorted_and_deduplicated(cell: u32, descriptor: u32) -> u32 {
    let element = descriptor::member(descriptor, 0);
    let held = __souther_list_length(cell);
    // An insertion sort: a set is written out by hand and is small, and the arena has nowhere to
    // put the second half of a merge.
    for i in 1..held {
        let mut j = i;
        while j > 0
            && order::compare(
                __souther_list_get(cell, j - 1),
                __souther_list_get(cell, j),
                element,
            ) > 0
        {
            let earlier = __souther_list_get(cell, j - 1);
            __souther_list_set(cell, j - 1, __souther_list_get(cell, j));
            __souther_list_set(cell, j, earlier);
            j -= 1;
        }
    }
    let mut kept = 0;
    for i in 0..held {
        let each = __souther_list_get(cell, i);
        if kept == 0
            || order::compare(__souther_list_get(cell, kept - 1), each, element) != 0
        {
            __souther_list_set(cell, kept, each);
            kept += 1;
        }
    }
    let out = __souther_list(descriptor, kept);
    for i in 0..kept {
        __souther_list_set(out, i, __souther_list_get(cell, i));
    }
    out
}

/// A map is written as an object, its keys the keys and its entries in ascending order of them.
///
/// A key written twice names one entry, and the one that stands is the last written: what reaches
/// a decoder is what the document says at that key, and a document says it last.
unsafe fn map(value: u32, descriptor: u32, path: u32, path_length: u32) -> u32 {
    let tag = json::__souther_json_tag(value);
    if tag != json::TAG_OBJECT {
        issues::issue(CODE_TYPE_MISMATCH, path, path_length, kind_of(tag), b"an object");
        return 0;
    }
    let held = json::__souther_json_length(value);
    let cell = __souther_map(descriptor, held);
    let values = descriptor::member(descriptor, 1);
    let mut whole = true;
    let mut kept = 0;
    for i in 0..held {
        let written = json::__souther_json_key(value, i);
        let name = json::__souther_json_bytes(written);
        let name_length = json::__souther_json_length(written);
        let (at, at_length) = below(path, path_length, (name, name_length));
        let read = __souther_read(json::__souther_json_value(value, i), values, at, at_length);
        if read == 0 {
            whole = false;
        }
        let key = __souther_string(name, name_length);
        let mut over = kept;
        for j in 0..kept {
            let existing = __souther_map_key(cell, j);
            if same(
                __souther_string_bytes(existing),
                __souther_string_length(existing),
                name,
                name_length,
            ) {
                over = j;
                break;
            }
        }
        __souther_map_set(cell, over, key, read);
        if over == kept {
            kept += 1;
        }
    }
    map_of_length(cell, kept);
    if !whole {
        return 0;
    }
    sorted_by_key(cell);
    cell
}

/// A map's entries, ascending by key.
unsafe fn sorted_by_key(cell: u32) {
    let held = __souther_map_length(cell);
    for i in 1..held {
        let mut j = i;
        while j > 0
            && order::compare_text(__souther_map_key(cell, j - 1), __souther_map_key(cell, j)) > 0
        {
            let key = __souther_map_key(cell, j - 1);
            let held_value = __souther_map_value(cell, j - 1);
            __souther_map_set(cell, j - 1, __souther_map_key(cell, j), __souther_map_value(cell, j));
            __souther_map_set(cell, j, key, held_value);
            j -= 1;
        }
    }
}

/// Where there is no key to be missing, `null` is the whole of what absence is.
unsafe fn option(value: u32, descriptor: u32, path: u32, path_length: u32) -> u32 {
    if json::__souther_json_tag(value) == json::TAG_NULL {
        return __souther_none();
    }
    let held = __souther_read(value, descriptor::member(descriptor, 0), path, path_length);
    if held == 0 {
        return 0;
    }
    __souther_some(held)
}

/// A set of alternatives that each carry nothing is written as the name of the one it is.
///
/// Nothing to stand beside, so nothing stands beside it: the tag is the value rather than a key in
/// an object holding the value.
unsafe fn enumeration(value: u32, descriptor: u32, path: u32, path_length: u32) -> u32 {
    let tag = json::__souther_json_tag(value);
    if tag != json::TAG_STRING {
        issues::issue(CODE_TYPE_MISMATCH, path, path_length, kind_of(tag), b"a case");
        return 0;
    }
    let held = json::__souther_json_bytes(value);
    let held_length = json::__souther_json_length(value);
    for i in 0..descriptor::arity(descriptor) {
        let (case, case_length) = descriptor::name(descriptor, i);
        if same(case, case_length, held, held_length) {
            return __souther_unit(descriptor::member(descriptor, i));
        }
    }
    issues::issue_of(
        CODE_NOT_ALLOWED,
        path,
        path_length,
        (held, held_length),
        (b"a case".as_ptr() as u32, 6),
    );
    0
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
        let (at, at_length) = below(path, path_length, discriminator());
        issues::issue(CODE_MISSING_FIELD, at, at_length, b"nothing", b"a case");
        return 0;
    }
    if json::__souther_json_tag(written) != json::TAG_STRING {
        let (at, at_length) = below(path, path_length, discriminator());
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
    let (at, at_length) = below(path, path_length, discriminator());
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

/// Where the discriminator's name is, for a path built through it.
fn discriminator() -> (u32, u32) {
    (DISCRIMINATOR.as_ptr() as u32, DISCRIMINATOR.len() as u32)
}

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
unsafe fn below(path: u32, path_length: u32, step: (u32, u32)) -> (u32, u32) {
    let (step, step_length) = step;
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
        KIND_ENUMERATION => named(cell, descriptor),
        KIND_LIST | KIND_SET => {
            write(b"[");
            let element = descriptor::member(descriptor, 0);
            for i in 0..__souther_list_length(cell) {
                if i > 0 {
                    write(b",");
                }
                written(__souther_list_get(cell, i), element);
            }
            write(b"]");
        }
        KIND_MAP => {
            write(b"{");
            let values = descriptor::member(descriptor, 1);
            for i in 0..__souther_map_length(cell) {
                if i > 0 {
                    write(b",");
                }
                let key = __souther_map_key(cell, i);
                json::__souther_json_write_string(
                    __souther_string_bytes(key),
                    __souther_string_length(key),
                );
                write(b":");
                written(__souther_map_value(cell, i), values);
            }
            write(b"}");
        }
        KIND_OPTION => {
            if core::ptr::read_unaligned(cell as usize as *const u32) == TAG_NONE {
                write(b"null");
            } else {
                written(
                    core::ptr::read_unaligned((cell as usize + HEADER) as *const u32),
                    descriptor::member(descriptor, 0),
                );
            }
        }
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

/// The name of the alternative a value is, which for a set that carries nothing is the whole of it.
unsafe fn named(cell: u32, descriptor: u32) {
    let held = core::ptr::read_unaligned((cell as usize + 4) as *const u32);
    for i in 0..descriptor::arity(descriptor) {
        if descriptor::member(descriptor, i) == held {
            let (tag, tag_length) = descriptor::name(descriptor, i);
            json::__souther_json_write_string(tag, tag_length);
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
    let mut written_any = opened;
    for i in 0..descriptor::arity(descriptor) {
        let member = descriptor::member(descriptor, i);
        let held = __souther_record_get(cell, i);
        // An option in a field is absent by having no key, which is the way round from an option
        // anywhere else. Writing `null` here would say the key was there holding nothing.
        if descriptor::kind(member) == KIND_OPTION
            && core::ptr::read_unaligned(held as usize as *const u32) == TAG_NONE
        {
            continue;
        }
        if written_any {
            write(b",");
        }
        written_any = true;
        let (field, field_length) = descriptor::name(descriptor, i);
        json::__souther_json_write_string(field, field_length);
        write(b":");
        written(held, member);
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
