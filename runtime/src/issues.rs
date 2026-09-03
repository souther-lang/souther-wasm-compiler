//! What a decoder found wrong with what it was handed.
//!
//! An invariant violation is treated by where it happens. Inside a behavior there is no value and
//! no case for one, so the call ends. At the boundary it is bad input, which is an expected
//! outcome: the decoder answers with the issues it found, at their paths, and the caller decides
//! what to do about them. This file is the second of those.
//!
//! Every issue found is kept, not the first. A caller that fixes one field and is then told about
//! the next has been made to ask as many times as its document had mistakes, so a decode reads the
//! whole document and reports everything at once.
//!
//! What is carried is a code, a path and what was there against what was wanted. Not a sentence:
//! the message a person reads is written against the code, in whatever language they read, by
//! whoever is showing it. The codes are Raoh's, so a resolver that already writes sentences for a
//! JVM decoder's issues writes them for these.

use crate::json::packed;
use crate::{alloc, next_free};

/// A place held something other than what it was declared to hold.
pub const CODE_TYPE_MISMATCH: &[u8] = b"type_mismatch";
/// A number was outside what the declared type holds.
pub const CODE_OUT_OF_RANGE: &[u8] = b"out_of_range";
/// A collection had the wrong number of elements.
pub const CODE_INVALID_SIZE: &[u8] = b"invalid_size";

/// The first issue this call found, or zero.
static mut FIRST: u32 = 0;
/// The last, so that the next is appended in the order it was found.
static mut LAST: u32 = 0;
/// How many there are.
static mut COUNT: u32 = 0;

const OFF_CODE: usize = 0;
const OFF_CODE_LENGTH: usize = 4;
const OFF_PATH: usize = 8;
const OFF_PATH_LENGTH: usize = 12;
const OFF_ACTUAL: usize = 16;
const OFF_ACTUAL_LENGTH: usize = 20;
const OFF_EXPECTED: usize = 24;
const OFF_EXPECTED_LENGTH: usize = 28;
const OFF_NEXT: usize = 32;
const RECORD: usize = 36;

/// Forgets what an earlier call found.
///
/// Called as an export's body starts. The records themselves are the arena's and are gone when the
/// caller resets; what has to be forgotten here is the list's head, which is not.
#[no_mangle]
pub unsafe extern "C" fn __souther_issues_begin() {
    FIRST = 0;
    LAST = 0;
    COUNT = 0;
}

/// How many issues this call has found. Nothing but zero lets a body run.
#[no_mangle]
pub unsafe extern "C" fn __souther_issues_count() -> u32 {
    COUNT
}

/// Records one issue.
#[no_mangle]
pub unsafe extern "C" fn __souther_issue(
    code: u32,
    code_length: u32,
    path: u32,
    path_length: u32,
    actual: u32,
    actual_length: u32,
    expected: u32,
    expected_length: u32,
) {
    let record = alloc(RECORD as u32);
    put(record, OFF_CODE, code);
    put(record, OFF_CODE_LENGTH, code_length);
    put(record, OFF_PATH, path);
    put(record, OFF_PATH_LENGTH, path_length);
    put(record, OFF_ACTUAL, actual);
    put(record, OFF_ACTUAL_LENGTH, actual_length);
    put(record, OFF_EXPECTED, expected);
    put(record, OFF_EXPECTED_LENGTH, expected_length);
    put(record, OFF_NEXT, 0);
    if FIRST == 0 {
        FIRST = record;
    } else {
        put(LAST, OFF_NEXT, record);
    }
    LAST = record;
    COUNT += 1;
}

/// Records one issue whose words are all literals.
pub unsafe fn issue(code: &[u8], path: u32, path_length: u32, actual: &[u8], expected: &[u8]) {
    __souther_issue(
        code.as_ptr() as u32,
        code.len() as u32,
        path,
        path_length,
        actual.as_ptr() as u32,
        actual.len() as u32,
        expected.as_ptr() as u32,
        expected.len() as u32,
    );
}

/// Records one issue whose `actual` and `expected` were written for it rather than being
/// literals here.
pub unsafe fn issue_of(
    code: &[u8],
    path: u32,
    path_length: u32,
    actual: (u32, u32),
    expected: (u32, u32),
) {
    __souther_issue(
        code.as_ptr() as u32,
        code.len() as u32,
        path,
        path_length,
        actual.0,
        actual.1,
        expected.0,
        expected.1,
    );
}

/// The issues as the JSON a caller reads, answered as a pointer and a length packed.
#[no_mangle]
pub unsafe extern "C" fn __souther_issues_written() -> u64 {
    let out = next_free();
    write(b"{\"issues\":[");
    let mut record = FIRST;
    let mut first = true;
    while record != 0 {
        if !first {
            write(b",");
        }
        first = false;
        write(b"{\"path\":");
        quoted(get(record, OFF_PATH), get(record, OFF_PATH_LENGTH));
        write(b",\"code\":");
        quoted(get(record, OFF_CODE), get(record, OFF_CODE_LENGTH));
        write(b",\"meta\":{\"actual\":");
        quoted(get(record, OFF_ACTUAL), get(record, OFF_ACTUAL_LENGTH));
        write(b",\"expected\":");
        quoted(get(record, OFF_EXPECTED), get(record, OFF_EXPECTED_LENGTH));
        write(b"}}");
        record = get(record, OFF_NEXT);
    }
    write(b"]}");
    packed(out, next_free() - out)
}

/// Appends bytes to the run being written at the arena's top.
///
/// Written straight onto the top rather than into a buffer sized in advance. Nothing else
/// allocates between the first byte and the last, so what the arena hands out each time is where
/// the previous piece ended, and the pieces are one run.
unsafe fn write(bytes: &[u8]) {
    let at = alloc(bytes.len() as u32);
    core::ptr::copy_nonoverlapping(bytes.as_ptr(), at as *mut u8, bytes.len());
}

/// Appends text as a quoted JSON string. What it answers is where it put the bytes, which is the
/// end of the run so far and so already known.
unsafe fn quoted(pointer: u32, length: u32) {
    crate::json::__souther_json_write_string(pointer, length);
}

unsafe fn get(record: u32, offset: usize) -> u32 {
    core::ptr::read_unaligned((record as usize + offset) as *const u32)
}

unsafe fn put(record: u32, offset: usize, value: u32) {
    core::ptr::write_unaligned((record as usize + offset) as *mut u32, value);
}
