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
//! What is carried is Raoh's issue, as the language says the boundary's failures are written (spec
//! §decoder-error): a path, a code, the message key that says which of the code's constraints it
//! was, and the metadata that constraint carries. Not a sentence: the message a person reads is
//! written against the message key, in whatever language they read, by whoever is showing it, and
//! a resolver that writes sentences for a JVM decoder's issues writes them for these.

use crate::alloc;
use crate::json::packed;
use crate::text;

/// Nothing is there, where something is required: an absent member, or `null`.
pub const CODE_REQUIRED: &[u8] = b"required";
/// A place held another kind of value than the one it was declared to hold.
pub const CODE_TYPE_MISMATCH: &[u8] = b"type_mismatch";
/// A whole number written wider than what the declared type holds.
pub const KEY_NUMERIC_RANGE: &[u8] = b"type_mismatch.numeric_range";
/// A string that denotes no value of the declared type: text that is no `String`, no temporal, or
/// the name of no case.
pub const CODE_INVALID_FORMAT: &[u8] = b"invalid_format";
/// A collection had the wrong number of elements.
pub const CODE_INVALID_SIZE: &[u8] = b"invalid_size";
/// A tag that names none of the cases the declaration offers.
pub const CODE_NOT_ALLOWED: &[u8] = b"not_allowed";
/// A value was written that a rule of its type, which no constraint states, says nothing may be.
pub const CODE_INVARIANT_VIOLATION: &[u8] = b"invariant_violation";

/// The first issue this call found, or zero.
static mut FIRST: u32 = 0;
/// The last, so that the next is appended in the order it was found.
static mut LAST: u32 = 0;
/// How many there are.
static mut COUNT: u32 = 0;

const OFF_CODE: usize = 0;
const OFF_CODE_LENGTH: usize = 4;
const OFF_KEY: usize = 8;
const OFF_KEY_LENGTH: usize = 12;
const OFF_PATH: usize = 16;
const OFF_PATH_LENGTH: usize = 20;
const OFF_META: usize = 24;
const OFF_META_LENGTH: usize = 28;
const OFF_NEXT: usize = 32;
const RECORD: usize = 36;

/// Forgets what an earlier call found.
///
/// Called as an export's body starts, since two calls may share one arena. The records themselves
/// are the arena's; what has to be forgotten here is the list's head, which is not.
#[no_mangle]
pub unsafe extern "C" fn __souther_issues_begin() {
    forget();
}

/// Forgets the list, as an export starts and as the arena its records live in is popped.
pub(crate) unsafe fn forget() {
    FIRST = 0;
    LAST = 0;
    COUNT = 0;
    meta::forget();
}

/// How many issues this call has found. Nothing but zero lets a body run.
#[no_mangle]
pub unsafe extern "C" fn __souther_issues_count() -> u32 {
    COUNT
}

/// Records one issue whose message key is its code.
pub unsafe fn issue(code: &[u8], path: u32, path_length: u32, meta: (u32, u32)) {
    keyed(code, code, path, path_length, meta);
}

/// Records one issue under a message key of its own, which says which of its code's constraints it
/// was.
pub unsafe fn keyed(code: &[u8], key: &[u8], path: u32, path_length: u32, meta: (u32, u32)) {
    let record = alloc(RECORD as u32);
    put(record, OFF_CODE, code.as_ptr() as u32);
    put(record, OFF_CODE_LENGTH, code.len() as u32);
    put(record, OFF_KEY, key.as_ptr() as u32);
    put(record, OFF_KEY_LENGTH, key.len() as u32);
    put(record, OFF_PATH, path);
    put(record, OFF_PATH_LENGTH, path_length);
    put(record, OFF_META, meta.0);
    put(record, OFF_META_LENGTH, meta.1);
    put(record, OFF_NEXT, 0);
    if FIRST == 0 {
        FIRST = record;
    } else {
        put(LAST, OFF_NEXT, record);
    }
    LAST = record;
    COUNT += 1;
}

/// The issues as the JSON a caller reads, answered as a pointer and a length packed.
#[no_mangle]
pub unsafe extern "C" fn __souther_issues_written() -> u64 {
    text::begin();
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
        write(b",\"messageKey\":");
        quoted(get(record, OFF_KEY), get(record, OFF_KEY_LENGTH));
        write(b",\"meta\":");
        text::push(get(record, OFF_META), get(record, OFF_META_LENGTH));
        write(b"}");
        record = get(record, OFF_NEXT);
    }
    write(b"]}");
    let (at, length) = text::ended();
    packed(at, length)
}

/// Appends bytes to the run being written at the arena's top.
unsafe fn write(bytes: &[u8]) {
    text::put(bytes);
}

/// Appends text as a quoted JSON string.
unsafe fn quoted(pointer: u32, length: u32) {
    let written = crate::json::__souther_json_write_string(pointer, length);
    text::push(written as u32, (written >> 32) as u32);
}

unsafe fn get(record: u32, offset: usize) -> u32 {
    core::ptr::read_unaligned((record as usize + offset) as *const u32)
}

unsafe fn put(record: u32, offset: usize, value: u32) {
    core::ptr::write_unaligned((record as usize + offset) as *mut u32, value);
}

/// An issue's metadata, written as the JSON object it is read as.
///
/// Written into a buffer of its own rather than the run an answer is written into: an issue is
/// raised while a document is being read, and what it says may be a value written out for it — the
/// duplicates a list held — which is written through that run. The two must not share a buffer.
pub mod meta {
    use crate::alloc;

    static mut AT: u32 = 0;
    static mut WRITTEN: u32 = 0;
    static mut ROOM: u32 = 0;
    static mut ENTRIES: u32 = 0;

    /// Forgets the object being written, as the arena it lives in is popped.
    pub(crate) unsafe fn forget() {
        AT = 0;
        WRITTEN = 0;
        ROOM = 0;
        ENTRIES = 0;
    }

    /// Starts an object.
    pub unsafe fn begin() {
        AT = alloc(64);
        WRITTEN = 0;
        ROOM = 64;
        ENTRIES = 0;
        put(b"{");
    }

    /// An object with nothing in it.
    pub unsafe fn none() -> (u32, u32) {
        begin();
        end()
    }

    /// Ends the object, answering where it is and how long.
    pub unsafe fn end() -> (u32, u32) {
        put(b"}");
        (AT, WRITTEN)
    }

    /// An entry whose value is text, quoted as JSON writes it.
    pub unsafe fn text(key: &[u8], at: u32, length: u32) {
        name(key);
        let written = crate::json::__souther_json_write_string(at, length);
        push(written as u32, (written >> 32) as u32);
    }

    /// An entry whose value is text this crate wrote down.
    pub unsafe fn word(key: &[u8], value: &[u8]) {
        text(key, value.as_ptr() as u32, value.len() as u32);
    }

    /// An entry whose value is a whole number.
    pub unsafe fn integer(key: &[u8], value: i64) {
        name(key);
        let mut digits = [0u8; 20];
        let mut at = digits.len();
        let negative = value < 0;
        let mut rest = value.unsigned_abs();
        loop {
            at -= 1;
            digits[at] = b'0' + (rest % 10) as u8;
            rest /= 10;
            if rest == 0 {
                break;
            }
        }
        if negative {
            put(b"-");
        }
        put(&digits[at..]);
    }

    /// An entry whose value is JSON already written: a number as it was written, a list.
    pub unsafe fn raw(key: &[u8], at: u32, length: u32) {
        name(key);
        push(at, length);
    }

    /// An entry whose value is a list of texts, each where it is and how long.
    pub unsafe fn texts(key: &[u8], each: impl Iterator<Item = (u32, u32)>) {
        name(key);
        put(b"[");
        let mut first = true;
        for (at, length) in each {
            if !first {
                put(b",");
            }
            first = false;
            let written = crate::json::__souther_json_write_string(at, length);
            push(written as u32, (written >> 32) as u32);
        }
        put(b"]");
    }

    unsafe fn name(key: &[u8]) {
        if ENTRIES > 0 {
            put(b",");
        }
        ENTRIES += 1;
        put(b"\"");
        put(key);
        put(b"\":");
    }

    unsafe fn put(bytes: &[u8]) {
        push(bytes.as_ptr() as u32, bytes.len() as u32);
    }

    unsafe fn push(from: u32, length: u32) {
        if WRITTEN + length > ROOM {
            let wanted = (WRITTEN + length) * 2;
            let wider = alloc(wanted);
            if WRITTEN > 0 {
                core::ptr::copy_nonoverlapping(AT as *const u8, wider as *mut u8, WRITTEN as usize);
            }
            AT = wider;
            ROOM = wanted;
        }
        core::ptr::copy_nonoverlapping(from as *const u8, (AT + WRITTEN) as *mut u8, length as usize);
        WRITTEN += length;
    }
}
