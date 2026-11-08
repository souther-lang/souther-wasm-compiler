//! A value crossing a typed boundary: laid out as the Canonical ABI lays it out, rather than written
//! as JSON text.
//!
//! What a caller hands in is made into the document the JSON boundary would have parsed, and read
//! by the readers that read that one. So everything a value is held to beyond its shape — a
//! newtype's rules, a set's distinct members, the text of an amount or a date — is held to here by
//! the same reading, and an issue names the same place it would there. What a typed caller cannot
//! get wrong (a field missing, a string where a number goes) the Canonical ABI has already refused;
//! what it can still get wrong, this reports.
//!
//! What goes back out is written straight into the Canonical ABI's layout, by a writer of its own:
//! a writer of JSON text writes text, and the two share the walk of a value and nothing else.
//!
//! Where anything lies is the compiler's to say and nowhere here. A descriptor of a type that
//! crosses typed carries where its layout is ([`crate::descriptor::layout`]):
//!
//! ```text
//! +0  u32 size              how many bytes a value takes in memory
//! +4  u32 alignment
//! +8  u32 flats             how many core values it flattens to
//! +12 u32 discriminant      how many bytes say which case a variant is
//! +16 u32 payload           where a variant's case lies; a map's entry's size
//! +20 u32 per member, where it lies: a product's fields; a map's entry's key and value
//! ```
//!
//! A flattened value comes in as core values spilled into eight-byte slots, one each, whatever
//! core type each is. A variant's cases share the slots after the one saying which case it is, so
//! a case is read from those and the variant's own count says where the next value starts.

use crate::descriptor::{
    self, Carried, KIND_BOOL, KIND_DATE, KIND_DATE_TIME, KIND_DECIMAL, KIND_ENUMERATION,
    KIND_INSTANT, KIND_INT, KIND_LIST, KIND_MAP, KIND_NEWTYPE, KIND_OPTION, KIND_PRODUCT, KIND_SET,
    KIND_STRING, KIND_SUM, KIND_TIME, KIND_UNIT,
};
use crate::issues;
use crate::json::{self, TAG_ARRAY, TAG_FALSE, TAG_NULL, TAG_NUMBER, TAG_OBJECT, TAG_STRING, TAG_TRUE};
use crate::value::{
    self, __souther_bool_value, __souther_int_value, __souther_list_get, __souther_list_length,
    __souther_map_key, __souther_map_length, __souther_map_value, __souther_record_get,
    __souther_string_bytes, __souther_string_length, TAG_NONE,
};
use crate::{abort, alloc_aligned, decimal, temporal, REASON_BACKEND_INVARIANT_BROKEN};

const L_SIZE: u32 = 0;
const L_ALIGN: u32 = 4;
const L_FLATS: u32 = 8;
const L_DISCRIMINANT: u32 = 12;
const L_PAYLOAD: u32 = 16;
const L_OFFSETS: u32 = 20;

/// What a function's arguments are, as the compiler wrote it for one function:
///
/// ```text
/// +0  u32 how many
/// +4  u32 whether they lie in memory, as a record of them, rather than in slots
/// +8  per argument: u32 its descriptor, u32 where it lies in that record
/// ```
const A_ARITY: u32 = 0;
const A_IN_MEMORY: u32 = 4;
const A_ARGUMENTS: u32 = 8;

/// What a function that can refuse answers, a `result` of its answer and a refusal, as the
/// compiler wrote it for one function:
///
/// ```text
/// +0  u32 size  +4 u32 alignment  +8 u32 where the ok or the err lies
/// +12 u32 where the program's protocol layout is, which is the same for every function
/// ```
const R_SIZE: u32 = 0;
const R_ALIGN: u32 = 4;
const R_PAYLOAD: u32 = 8;
const R_PROTOCOL: u32 = 12;

/// Where the parts of a refusal lie, the same for every function a program offers:
///
/// ```text
/// +0  where a refusal's case lies
/// +4  where an ended's reason lies          +8  where its option of an invalid answer lies
/// +12 where that option's answer lies
/// +16 where an invalid answer's behavior lies +20 where its issues lie
/// +24 an issue's size  +28 its alignment
/// +32 where its path, code, message key, meta and message lie, five words
/// +52 where the message option's text lies
/// ```
const P_REFUSAL_PAYLOAD: u32 = 0;
const P_ENDED_REASON: u32 = 4;
const P_ENDED_INVALID: u32 = 8;
const P_OPTION_PAYLOAD: u32 = 12;
const P_INVALID_BEHAVIOR: u32 = 16;
const P_INVALID_ISSUES: u32 = 20;
const P_ISSUE_SIZE: u32 = 24;
const P_ISSUE_ALIGN: u32 = 28;
const P_ISSUE_PATH: u32 = 32;
const P_ISSUE_CODE: u32 = 36;
const P_ISSUE_KEY: u32 = 40;
const P_ISSUE_META: u32 = 44;
const P_ISSUE_MESSAGE: u32 = 48;
const P_MESSAGE_PAYLOAD: u32 = 52;

/// The case of a `result` that answers.
const OK: u8 = 0;
/// The case of a `result` that refuses.
const ERR: u8 = 1;
/// The case of a refusal that is issues.
const REFUSED: u8 = 0;
/// The case of a refusal that is a call that ended.
const ENDED: u8 = 1;

unsafe fn word(at: u32) -> u32 {
    core::ptr::read_unaligned(at as usize as *const u32)
}

unsafe fn put_word(at: u32, held: u32) {
    core::ptr::write_unaligned(at as usize as *mut u32, held);
}

unsafe fn put_byte(at: u32, held: u8) {
    core::ptr::write(at as usize as *mut u8, held);
}

unsafe fn slot(slots: u32, index: u32) -> u64 {
    core::ptr::read_unaligned((slots + 8 * index) as usize as *const u64)
}

unsafe fn put_slot(slots: u32, index: u32, held: u64) {
    core::ptr::write_unaligned((slots + 8 * index) as usize as *mut u64, held);
}

unsafe fn layout(descriptor: u32) -> u32 {
    let at = descriptor::layout(descriptor);
    if at == 0 {
        abort(REASON_BACKEND_INVARIANT_BROKEN, descriptor, 0, 0);
    }
    at
}

unsafe fn size(descriptor: u32) -> u32 {
    word(layout(descriptor) + L_SIZE)
}

unsafe fn alignment(descriptor: u32) -> u32 {
    word(layout(descriptor) + L_ALIGN)
}

unsafe fn flats(descriptor: u32) -> u32 {
    word(layout(descriptor) + L_FLATS)
}

unsafe fn payload(descriptor: u32) -> u32 {
    word(layout(descriptor) + L_PAYLOAD)
}

unsafe fn offset(descriptor: u32, index: u32) -> u32 {
    word(layout(descriptor) + L_OFFSETS + 4 * index)
}

/// Which case a variant laid out at `at` is, in as many bytes as its layout says.
unsafe fn case_at(at: u32, descriptor: u32) -> u32 {
    let case = match word(layout(descriptor) + L_DISCRIMINANT) {
        1 => core::ptr::read(at as usize as *const u8) as u32,
        2 => core::ptr::read_unaligned(at as usize as *const u16) as u32,
        _ => word(at),
    };
    within(case, descriptor)
}

unsafe fn put_case(at: u32, descriptor: u32, case: u32) {
    match word(layout(descriptor) + L_DISCRIMINANT) {
        1 => put_byte(at, case as u8),
        2 => core::ptr::write_unaligned(at as usize as *mut u16, case as u16),
        _ => put_word(at, case),
    }
}

/// A case the Canonical ABI lifted is one of the variant's, which it checks; one past them is
/// this runtime and the compiler disagreeing about how many there are.
unsafe fn within(case: u32, descriptor: u32) -> u32 {
    let cases = if descriptor::kind(descriptor) == KIND_OPTION { 2 } else { descriptor::arity(descriptor) };
    if case >= cases {
        abort(REASON_BACKEND_INVARIANT_BROKEN, descriptor, case as u64, 0);
    }
    case
}

unsafe fn text_cell(tag: u32, at: u32, length: u32) -> u32 {
    json::made_of_bytes(tag, at, length)
}

/// A string read where a number is written: an amount, which the JSON boundary reads from a
/// number. Text that is no number is handed on as a string, which the reader refuses as a place
/// holding the wrong kind of value, rather than as a number it is not ready for.
unsafe fn amount_cell(at: u32, length: u32) -> u32 {
    let tag = if json::is_number(at, length) { TAG_NUMBER } else { TAG_STRING };
    text_cell(tag, at, length)
}

unsafe fn integer_cell(held: i64) -> u32 {
    let packed = json::__souther_json_write_int(held);
    text_cell(TAG_NUMBER, packed as u32, (packed >> 32) as u32)
}

/// What a variant's case is read as: a unit's object holding the tag alone, a shape's fields beside
/// the tag, and anything else under the key a case carried as itself stands under.
unsafe fn tagged(descriptor: u32, case: u32, carried: impl FnOnce(u32) -> u32) -> u32 {
    let member = descriptor::member(descriptor, case);
    let (tag_key, tag_key_length) = descriptor::tag_key(descriptor);
    let (tag, tag_length) = descriptor::name(descriptor, case);
    let key = text_cell(TAG_STRING, tag_key, tag_key_length);
    let tag = text_cell(TAG_STRING, tag, tag_length);
    match descriptor::carried(member) {
        Carried::Nothing => {
            let object = json::made_holding(TAG_OBJECT, 1);
            json::put_entry(object, 0, key, tag);
            object
        }
        Carried::Fields => {
            let fields = carried(member);
            let count = json::__souther_json_length(fields);
            let object = json::made_holding(TAG_OBJECT, count + 1);
            json::put_entry(object, 0, key, tag);
            for i in 0..count {
                json::put_entry(
                    object,
                    i + 1,
                    json::__souther_json_key(fields, i),
                    json::__souther_json_value(fields, i),
                );
            }
            object
        }
        Carried::Itself => {
            let (contents, contents_length) = descriptor::contents_key(descriptor);
            let object = json::made_holding(TAG_OBJECT, 2);
            json::put_entry(object, 0, key, tag);
            json::put_entry(
                object,
                1,
                text_cell(TAG_STRING, contents, contents_length),
                carried(member),
            );
            object
        }
    }
}

/// A product's fields as an object, each read by `field` and one an option holds nothing in left
/// out, as the JSON boundary writes absence in a field.
unsafe fn fields(descriptor: u32, mut field: impl FnMut(u32, u32) -> u32) -> u32 {
    let count = descriptor::arity(descriptor);
    let object = json::made_holding(TAG_OBJECT, count);
    let mut held = 0;
    for i in 0..count {
        let value = field(i, descriptor::member(descriptor, i));
        if value == 0 {
            continue;
        }
        let (name, name_length) = descriptor::name(descriptor, i);
        json::put_entry(object, held, text_cell(TAG_STRING, name, name_length), value);
        held += 1;
    }
    json::holds(object, held);
    object
}

/// The document a value laid out in memory at `at` would have been written as.
unsafe fn loaded(at: u32, descriptor: u32) -> u32 {
    match descriptor::kind(descriptor) {
        KIND_INT => integer_cell(core::ptr::read_unaligned(at as usize as *const i64)),
        KIND_BOOL => json::made(if core::ptr::read(at as usize as *const u8) != 0 { TAG_TRUE } else { TAG_FALSE }),
        KIND_STRING | KIND_DATE | KIND_TIME | KIND_DATE_TIME | KIND_INSTANT => {
            text_cell(TAG_STRING, word(at), word(at + 4))
        }
        KIND_DECIMAL => amount_cell(word(at), word(at + 4)),
        KIND_UNIT => json::made_holding(TAG_OBJECT, 0),
        KIND_NEWTYPE => loaded(at, descriptor::member(descriptor, 0)),
        KIND_PRODUCT => fields(descriptor, |i, member| {
            let place = at + offset(descriptor, i);
            if descriptor::kind(member) == KIND_OPTION {
                if case_at(place, member) == 0 {
                    return 0;
                }
                return loaded(place + payload(member), descriptor::member(member, 0));
            }
            loaded(place, member)
        }),
        KIND_SUM => {
            let case = case_at(at, descriptor);
            tagged(descriptor, case, |member| loaded(at + payload(descriptor), member))
        }
        KIND_ENUMERATION => {
            let (name, name_length) = descriptor::name(descriptor, case_at(at, descriptor));
            text_cell(TAG_STRING, name, name_length)
        }
        KIND_OPTION => {
            if case_at(at, descriptor) == 0 {
                json::made(TAG_NULL)
            } else {
                loaded(at + payload(descriptor), descriptor::member(descriptor, 0))
            }
        }
        KIND_LIST | KIND_SET => elements(word(at), word(at + 4), descriptor),
        KIND_MAP => entries(word(at), word(at + 4), descriptor),
        other => abort(REASON_BACKEND_INVARIANT_BROKEN, descriptor, other as u64, 0),
    }
}

unsafe fn elements(at: u32, count: u32, descriptor: u32) -> u32 {
    let element = descriptor::member(descriptor, 0);
    let stride = size(element);
    let array = json::made_holding(TAG_ARRAY, count);
    for i in 0..count {
        json::put_element(array, i, loaded(at + i * stride, element));
    }
    array
}

/// A map's entries as the object the JSON boundary reads one from: each key as the text it would
/// have been written as, which is what reads it as a key; and every entry as it was handed in, a
/// key handed twice included, which the reading settles as it settles one written twice.
unsafe fn entries(at: u32, count: u32, descriptor: u32) -> u32 {
    let keys = descriptor::member(descriptor, 0);
    let values = descriptor::member(descriptor, 1);
    let stride = payload(descriptor);
    let object = json::made_holding(TAG_OBJECT, count);
    for i in 0..count {
        let entry = at + i * stride;
        json::put_entry(
            object,
            i,
            loaded(entry + offset(descriptor, 0), keys),
            loaded(entry + offset(descriptor, 1), values),
        );
    }
    object
}

/// The document a value flattened into slots from `*index` on would have been written as, moving
/// `*index` past it.
unsafe fn lifted(slots: u32, index: &mut u32, descriptor: u32) -> u32 {
    let first = *index;
    let read = match descriptor::kind(descriptor) {
        KIND_INT => integer_cell(slot(slots, first) as i64),
        KIND_BOOL => json::made(if slot(slots, first) as u32 != 0 { TAG_TRUE } else { TAG_FALSE }),
        KIND_STRING | KIND_DATE | KIND_TIME | KIND_DATE_TIME | KIND_INSTANT => {
            text_cell(TAG_STRING, slot(slots, first) as u32, slot(slots, first + 1) as u32)
        }
        KIND_DECIMAL => amount_cell(slot(slots, first) as u32, slot(slots, first + 1) as u32),
        KIND_UNIT => json::made_holding(TAG_OBJECT, 0),
        KIND_NEWTYPE => {
            *index = first;
            return lifted(slots, index, descriptor::member(descriptor, 0));
        }
        KIND_PRODUCT => {
            let mut at = first;
            fields(descriptor, |_, member| {
                if descriptor::kind(member) == KIND_OPTION {
                    let past = at + flats(member);
                    let held = slot(slots, at) as u32;
                    let value = if within(held, member) == 0 {
                        0
                    } else {
                        let mut inside = at + 1;
                        lifted(slots, &mut inside, descriptor::member(member, 0))
                    };
                    at = past;
                    return value;
                }
                lifted(slots, &mut at, member)
            })
        }
        KIND_SUM => {
            let case = within(slot(slots, first) as u32, descriptor);
            tagged(descriptor, case, |member| {
                let mut inside = first + 1;
                lifted(slots, &mut inside, member)
            })
        }
        KIND_ENUMERATION => {
            let case = within(slot(slots, first) as u32, descriptor);
            let (name, name_length) = descriptor::name(descriptor, case);
            text_cell(TAG_STRING, name, name_length)
        }
        KIND_OPTION => {
            if within(slot(slots, first) as u32, descriptor) == 0 {
                json::made(TAG_NULL)
            } else {
                let mut inside = first + 1;
                lifted(slots, &mut inside, descriptor::member(descriptor, 0))
            }
        }
        KIND_LIST | KIND_SET => {
            elements(slot(slots, first) as u32, slot(slots, first + 1) as u32, descriptor)
        }
        KIND_MAP => entries(slot(slots, first) as u32, slot(slots, first + 1) as u32, descriptor),
        other => abort(REASON_BACKEND_INVARIANT_BROKEN, descriptor, other as u64, 0),
    };
    *index = first + flats(descriptor);
    read
}

/// Room for `count` core values spilled one to a slot, every slot nothing until something is put
/// in it: a variant's case that flattens to fewer than the variant leaves the rest as nothing.
#[no_mangle]
pub unsafe extern "C" fn __souther_typed_slots(count: u32) -> u32 {
    let at = alloc_aligned(8 * count, 8);
    core::ptr::write_bytes(at as usize as *mut u8, 0, 8 * count as usize);
    at
}

/// A function's arguments, as the array of them the JSON boundary reads a call's arguments from:
/// read from the slots they were spilled into, or from the record of them in memory where they
/// were too many to flatten.
#[no_mangle]
pub unsafe extern "C" fn __souther_typed_arguments(at: u32, arguments: u32) -> u32 {
    let arity = word(arguments + A_ARITY);
    let in_memory = word(arguments + A_IN_MEMORY) != 0;
    let array = json::made_holding(TAG_ARRAY, arity);
    let mut index = 0;
    for i in 0..arity {
        let descriptor = word(arguments + A_ARGUMENTS + 8 * i);
        let read = if in_memory {
            loaded(at + word(arguments + A_ARGUMENTS + 8 * i + 4), descriptor)
        } else {
            lifted(at, &mut index, descriptor)
        };
        json::put_element(array, i, read);
    }
    array
}

/// Writes a value as the Canonical ABI lays it out, at `at`.
unsafe fn stored(cell: u32, descriptor: u32, at: u32) {
    match descriptor::kind(descriptor) {
        KIND_INT => core::ptr::write_unaligned(at as usize as *mut i64, __souther_int_value(cell)),
        KIND_BOOL => put_byte(at, (__souther_bool_value(cell) != 0) as u8),
        KIND_STRING => {
            put_word(at, __souther_string_bytes(cell));
            put_word(at + 4, __souther_string_length(cell));
        }
        KIND_DECIMAL => put_text(at, decimal::external(cell)),
        KIND_DATE => put_text(at, temporal::written_day(cell)),
        KIND_TIME => put_text(at, temporal::written_time(cell)),
        KIND_DATE_TIME => put_text(at, temporal::written_both(cell)),
        KIND_INSTANT => put_text(at, temporal::written_moment(cell)),
        KIND_UNIT => put_byte(at, 0),
        KIND_NEWTYPE => stored(__souther_record_get(cell, 0), descriptor::member(descriptor, 0), at),
        KIND_PRODUCT => {
            for i in 0..descriptor::arity(descriptor) {
                stored(__souther_record_get(cell, i), descriptor::member(descriptor, i), at + offset(descriptor, i));
            }
        }
        KIND_SUM => {
            let case = value::case_among(cell, descriptor);
            put_case(at, descriptor, case);
            let member = descriptor::member(descriptor, case);
            if !matches!(descriptor::carried(member), Carried::Nothing) {
                stored(cell, member, at + payload(descriptor));
            }
        }
        KIND_ENUMERATION => put_case(at, descriptor, value::case_among(cell, descriptor)),
        KIND_OPTION => {
            if core::ptr::read_unaligned(cell as usize as *const u32) == TAG_NONE {
                put_case(at, descriptor, 0);
            } else {
                put_case(at, descriptor, 1);
                stored(value::__souther_held(cell), descriptor::member(descriptor, 0), at + payload(descriptor));
            }
        }
        KIND_LIST => {
            let element = descriptor::member(descriptor, 0);
            let count = __souther_list_length(cell);
            let room = room_for(count, element);
            for i in 0..count {
                stored(__souther_list_get(cell, i), element, room + i * size(element));
            }
            put_word(at, room);
            put_word(at + 4, count);
        }
        KIND_SET => {
            let element = descriptor::member(descriptor, 0);
            let count = __souther_list_length(cell);
            let room = room_for(count, element);
            let order = value::written_order(cell, element);
            for (k, &i) in order.iter().enumerate() {
                stored(__souther_list_get(cell, i), element, room + k as u32 * size(element));
            }
            put_word(at, room);
            put_word(at + 4, count);
        }
        KIND_MAP => {
            let keys = descriptor::member(descriptor, 0);
            let values = descriptor::member(descriptor, 1);
            let count = __souther_map_length(cell);
            let stride = payload(descriptor);
            let room = alloc_aligned(count * stride, alignment(descriptor));
            for i in 0..count {
                let entry = room + i * stride;
                stored(__souther_map_key(cell, i), keys, entry + offset(descriptor, 0));
                stored(__souther_map_value(cell, i), values, entry + offset(descriptor, 1));
            }
            put_word(at, room);
            put_word(at + 4, count);
        }
        other => abort(REASON_BACKEND_INVARIANT_BROKEN, descriptor, other as u64, cell as u64),
    }
}

/// Room for a list's elements, at the alignment its element is laid out at, which a caller reading
/// the list checks it begins at.
unsafe fn room_for(count: u32, element: u32) -> u32 {
    alloc_aligned(count * size(element), alignment(element))
}

/// Text a value is written as, copied out of wherever it was written into memory of its own: the
/// run text is written into is written over by the next one.
unsafe fn put_text(at: u32, (text, length): (u32, u32)) {
    let room = alloc_aligned(length, 1);
    core::ptr::copy_nonoverlapping(text as usize as *const u8, room as usize as *mut u8, length as usize);
    put_word(at, room);
    put_word(at + 4, length);
}

/// Writes a value flattened, into slots from `*index` on, moving `*index` past it.
unsafe fn flattened(cell: u32, descriptor: u32, slots: u32, index: &mut u32) {
    let first = *index;
    match descriptor::kind(descriptor) {
        KIND_INT => put_slot(slots, first, __souther_int_value(cell) as u64),
        KIND_BOOL => put_slot(slots, first, (__souther_bool_value(cell) != 0) as u64),
        KIND_NEWTYPE => {
            flattened(__souther_record_get(cell, 0), descriptor::member(descriptor, 0), slots, index);
            return;
        }
        KIND_PRODUCT => {
            let mut at = first;
            for i in 0..descriptor::arity(descriptor) {
                flattened(__souther_record_get(cell, i), descriptor::member(descriptor, i), slots, &mut at);
            }
        }
        KIND_SUM => {
            let case = value::case_among(cell, descriptor);
            put_slot(slots, first, case as u64);
            let member = descriptor::member(descriptor, case);
            if !matches!(descriptor::carried(member), Carried::Nothing) {
                let mut inside = first + 1;
                flattened(cell, member, slots, &mut inside);
            }
        }
        KIND_ENUMERATION => put_slot(slots, first, value::case_among(cell, descriptor) as u64),
        KIND_UNIT => put_slot(slots, first, 0),
        KIND_OPTION => {
            if core::ptr::read_unaligned(cell as usize as *const u32) == TAG_NONE {
                put_slot(slots, first, 0);
            } else {
                put_slot(slots, first, 1);
                let mut inside = first + 1;
                flattened(value::__souther_held(cell), descriptor::member(descriptor, 0), slots, &mut inside);
            }
        }
        // Everything else flattens to a pointer and a length, which is what it is laid out as.
        _ => {
            let room = alloc_aligned(8, 4);
            stored(cell, descriptor, room);
            put_slot(slots, first, word(room) as u64);
            put_slot(slots, first + 1, word(room + 4) as u64);
        }
    }
    *index = first + flats(descriptor);
}

/// Writes the arguments of a call out, flattened into slots or laid out as a record of them, as the
/// function the call goes to takes them.
#[no_mangle]
pub unsafe extern "C" fn __souther_typed_lowered(values: u32, arguments: u32, at: u32) {
    let arity = word(arguments + A_ARITY);
    let in_memory = word(arguments + A_IN_MEMORY) != 0;
    let mut index = 0;
    for i in 0..arity {
        let descriptor = word(arguments + A_ARGUMENTS + 8 * i);
        let cell = word(values + 4 * i);
        if in_memory {
            stored(cell, descriptor, at + word(arguments + A_ARGUMENTS + 8 * i + 4));
        } else {
            flattened(cell, descriptor, at, &mut index);
        }
    }
}

/// Where a call's typed answer is written, which the canonical ABI reads it out of before the
/// post-return gives the arena back.
unsafe fn area(result: u32) -> u32 {
    let at = alloc_aligned(word(result + R_SIZE), word(result + R_ALIGN));
    core::ptr::write_bytes(at as usize as *mut u8, 0, word(result + R_SIZE) as usize);
    at
}

/// The `ok` of a call's result: what the behavior answered.
#[no_mangle]
pub unsafe extern "C" fn __souther_typed_answered(cell: u32, descriptor: u32, result: u32) -> u32 {
    let at = area(result);
    put_byte(at, OK);
    stored(cell, descriptor, at + word(result + R_PAYLOAD));
    at
}

/// The `err` of a call's result that the boundary refused: the issues found, as records.
#[no_mangle]
pub unsafe extern "C" fn __souther_typed_refused(result: u32) -> u32 {
    let at = area(result);
    let protocol = word(result + R_PROTOCOL);
    let refusal = at + word(result + R_PAYLOAD);
    put_byte(at, ERR);
    put_byte(refusal, REFUSED);
    let (issues, count) = laid_out_issues(protocol);
    let place = refusal + word(protocol + P_REFUSAL_PAYLOAD);
    put_word(place, issues);
    put_word(place + 4, count);
    at
}

/// The issues this call found, as a list of the records a typed caller reads.
unsafe fn laid_out_issues(protocol: u32) -> (u32, u32) {
    let mut count = 0;
    issues::each(|_, _| count += 1);
    let size = word(protocol + P_ISSUE_SIZE);
    let room = alloc_aligned(count * size, word(protocol + P_ISSUE_ALIGN));
    core::ptr::write_bytes(room as usize as *mut u8, 0, (count * size) as usize);
    issues::each(|i, found| {
        let at = room + i * size;
        let put = |offset: u32, (text, length): (u32, u32)| {
            put_word(at + word(protocol + offset), text);
            put_word(at + word(protocol + offset) + 4, length);
        };
        put(P_ISSUE_PATH, found.path);
        put(P_ISSUE_CODE, found.code);
        put(P_ISSUE_KEY, found.key);
        put(P_ISSUE_META, found.meta);
        let message = at + word(protocol + P_ISSUE_MESSAGE);
        if found.message.1 > 0 {
            put_byte(message, 1);
            put_word(message + word(protocol + P_MESSAGE_PAYLOAD), found.message.0);
            put_word(message + word(protocol + P_MESSAGE_PAYLOAD) + 4, found.message.1);
        }
    });
    (room, count)
}

/// Where the `err` of a call that ended is written: static, and not the arena's, because writing
/// why a call ended must not be able to fail, and it may have ended because nothing more could be
/// handed out. Enough for the err of any function's result: the case, padding to at most eight,
/// and a refusal.
static mut ENDED_AREA: [u64; 8] = [0; 8];

/// The `err` of a call's result that the runtime ended: its reason, as the record holds it, and
/// for an answer a function given to the program answered outside its type, which behavior and
/// what is wrong with it.
///
/// Nothing is handed out for any reason but that one, which is not a call that ran out of room:
/// its issues were found reading an answer, and are laid out as a refused call's are.
#[no_mangle]
pub unsafe extern "C" fn __souther_typed_ended(result: u32) -> u32 {
    let at = core::ptr::addr_of_mut!(ENDED_AREA) as u32;
    core::ptr::write_bytes(at as usize as *mut u8, 0, 64);
    let protocol = word(result + R_PROTOCOL);
    let refusal = at + word(result + R_PAYLOAD);
    put_byte(at, ERR);
    put_byte(refusal, ENDED);
    let ended = refusal + word(protocol + P_REFUSAL_PAYLOAD);
    let reason = core::ptr::read((crate::FAILURE + crate::OFF_REASON) as *const u32);
    put_word(ended + word(protocol + P_ENDED_REASON), reason);
    if reason == crate::REASON_INVALID_HOST_ANSWER {
        let option = ended + word(protocol + P_ENDED_INVALID);
        put_byte(option, 1);
        let invalid = option + word(protocol + P_OPTION_PAYLOAD);
        let behavior = core::ptr::read_unaligned((crate::FAILURE + crate::OFF_AUX0) as *const u64);
        put_word(invalid + word(protocol + P_INVALID_BEHAVIOR), behavior as u32);
        put_word(invalid + word(protocol + P_INVALID_BEHAVIOR) + 4, (behavior >> 32) as u32);
        let (issues, count) = laid_out_issues(protocol);
        put_word(invalid + word(protocol + P_INVALID_ISSUES), issues);
        put_word(invalid + word(protocol + P_INVALID_ISSUES) + 4, count);
    }
    at
}
