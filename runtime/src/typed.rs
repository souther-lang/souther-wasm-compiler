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
//! +4  u32 alignment         what it is aligned to there
//! +8  u32 flats             how many core values it flattens to
//! +12 u32 discriminant      a variant's: how many bytes say which case it is
//! +16 u32 payload           a variant's: where what its case carries lies
//! +20 u32 cases             a variant's: how many cases it has
//! +24 u32 none              an option's: the case that holds nothing
//! +28 u32 some              an option's: the case that holds something
//! +32 u32 only              a unit's: the one case it is, as an enum of itself alone
//! +36 u32 entry size        a map's: how many bytes one entry, a tuple of a key and a value, takes
//! +40 u32 entry alignment   a map's: what an entry is aligned to, which its list's room begins at
//! +44 u32 key               a map's: where an entry's key lies in it
//! +48 u32 value             a map's: where an entry's value lies in it
//! +52 u32 per field, where it lies: a product's
//! ```
//!
//! Each word says one thing of one kind of type, and nothing for the others. A map's own value is
//! a list's pointer and length, and its entries are something else again, laid out and aligned as
//! a tuple; a word saying the one where the other was meant laid entries out at four that a caller
//! lifting them requires at eight.
//!
//! A sum's case and an enumeration's are numbered as the descriptor lists them, which is the order
//! the checker lists them in and the typed interfaces declare them in: both read the one list.
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
use crate::json::{
    self, TAG_AMOUNT_TEXT, TAG_ARRAY, TAG_FALSE, TAG_NULL, TAG_OBJECT, TAG_STRING, TAG_TRUE,
};
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
const L_CASES: u32 = 20;
const L_NONE: u32 = 24;
const L_SOME: u32 = 28;
const L_ONLY: u32 = 32;
const L_ENTRY_SIZE: u32 = 36;
const L_ENTRY_ALIGN: u32 = 40;
const L_KEY: u32 = 44;
const L_VALUE: u32 = 48;
const L_FIELDS: u32 = 52;

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
/// +16 u32 where the err of a call that ended is written, in static memory, and +20 how long
/// ```
const R_SIZE: u32 = 0;
const R_ALIGN: u32 = 4;
const R_PAYLOAD: u32 = 8;
const R_PROTOCOL: u32 = 12;
const R_ENDED: u32 = 16;
const R_ENDED_LENGTH: u32 = 20;

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
/// +56 a result: how many bytes say its case, the case that answers, the case that refuses
/// +68 a refusal: how many bytes say its case, the case of issues, the case of a call that ended
/// +80 an option: how many bytes say its case, the case that holds something
/// ```
///
/// Which case is which number, and in how many bytes, is the layout's as much as where a field
/// lies, so it is here with the rest and not written down a second time below.
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
const P_RESULT_WIDTH: u32 = 56;
const P_OK: u32 = 60;
const P_ERR: u32 = 64;
const P_REFUSAL_WIDTH: u32 = 68;
const P_ISSUES: u32 = 72;
const P_ENDED: u32 = 76;
const P_OPTION_WIDTH: u32 = 80;
const P_SOME: u32 = 84;

/// Writes which case a variant laid out at `at` is, as the protocol layout says of that variant:
/// in `width` bytes, the case at `case`.
unsafe fn put_protocol_case(at: u32, protocol: u32, width: u32, case: u32) {
    put_case_in(at, word(protocol + width), word(protocol + case));
}

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

/// Where a product's field lies in it.
unsafe fn field(descriptor: u32, index: u32) -> u32 {
    word(layout(descriptor) + L_FIELDS + 4 * index)
}

/// The one case a unit is, as an enum of itself alone.
unsafe fn only(descriptor: u32) -> u32 {
    word(layout(descriptor) + L_ONLY)
}

/// How many bytes a map's entry takes, which is how far one entry is from the next.
unsafe fn entry_size(descriptor: u32) -> u32 {
    word(layout(descriptor) + L_ENTRY_SIZE)
}

/// What a map's entry is aligned to.
unsafe fn entry_alignment(descriptor: u32) -> u32 {
    word(layout(descriptor) + L_ENTRY_ALIGN)
}

/// Where a map's entry's key lies in it.
unsafe fn key_at(descriptor: u32) -> u32 {
    word(layout(descriptor) + L_KEY)
}

/// Where a map's entry's value lies in it.
unsafe fn value_at(descriptor: u32) -> u32 {
    word(layout(descriptor) + L_VALUE)
}

/// The case of an option that holds nothing.
unsafe fn none(descriptor: u32) -> u32 {
    word(layout(descriptor) + L_NONE)
}

/// The case of an option that holds something.
unsafe fn some(descriptor: u32) -> u32 {
    word(layout(descriptor) + L_SOME)
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
    put_case_in(at, word(layout(descriptor) + L_DISCRIMINANT), case);
}

/// Writes a case in as many bytes as say which case a variant is.
unsafe fn put_case_in(at: u32, width: u32, case: u32) {
    match width {
        1 => put_byte(at, case as u8),
        2 => core::ptr::write_unaligned(at as usize as *mut u16, case as u16),
        _ => put_word(at, case),
    }
}

/// A case the Canonical ABI lifted is one of the variant's, which it checks; one past them is
/// this runtime and the compiler disagreeing about how many there are.
unsafe fn within(case: u32, descriptor: u32) -> u32 {
    if case >= word(layout(descriptor) + L_CASES) {
        abort(REASON_BACKEND_INVARIANT_BROKEN, descriptor, case as u64, 0);
    }
    case
}

unsafe fn text_cell(tag: u32, at: u32, length: u32) -> u32 {
    json::made_of_bytes(tag, at, length)
}

/// An amount, which a typed caller hands over as the text the JSON boundary writes one as. Whether
/// the text is an amount is the reader's to say, as it says whether a date's is a date: the text is
/// handed on as what it is, and not as a number it may not be nor as a string, which a reader of an
/// amount would refuse as a string standing where a number goes.
unsafe fn amount_cell(at: u32, length: u32) -> u32 {
    text_cell(TAG_AMOUNT_TEXT, at, length)
}

unsafe fn integer_cell(held: i64) -> u32 {
    json::made_of_int(held)
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
            let contents = descriptor::contents_key(descriptor);
            let object = json::made_holding(TAG_OBJECT, 2);
            json::put_entry(object, 0, key, tag);
            json::put_entry(
                object,
                1,
                text_cell(TAG_STRING, contents.0, contents.1),
                carried(member),
            );
            object
        }
    }
}

/// A product's fields as an object, each read by `field` at its place and one an option holds
/// nothing in left out, as the JSON boundary writes absence in a field.
unsafe fn fields(descriptor: u32, mut field: impl FnMut(u32, u32) -> u32) -> u32 {
    let count = descriptor::arity(descriptor);
    let object = json::made_holding(TAG_OBJECT, count);
    let mut held = 0;
    for i in 0..count {
        let name = descriptor::name(descriptor, i);
        let value = field(i, descriptor::member(descriptor, i));
        if value == 0 {
            continue;
        }
        json::put_entry(object, held, text_cell(TAG_STRING, name.0, name.1), value);
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
            let place = at + field(descriptor, i);
            if descriptor::kind(member) == KIND_OPTION {
                if case_at(place, member) == none(member) {
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
            if case_at(at, descriptor) == none(descriptor) {
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
/// key handed twice included, which the reading settles as it settles one written twice. A value
/// stands at its key, as a member of the object does.
unsafe fn entries(at: u32, count: u32, descriptor: u32) -> u32 {
    let keys = descriptor::member(descriptor, 0);
    let values = descriptor::member(descriptor, 1);
    let stride = entry_size(descriptor);
    let object = json::made_holding(TAG_OBJECT, count);
    for i in 0..count {
        let entry = at + i * stride;
        json::put_entry(
            object,
            i,
            loaded(entry + key_at(descriptor), keys),
            loaded(entry + value_at(descriptor), values),
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
                    let value = if within(held, member) == none(member) {
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
            if within(slot(slots, first) as u32, descriptor) == none(descriptor) {
                json::made(TAG_NULL)
            } else {
                let mut inside = first + 1;
                lifted(slots, &mut inside, descriptor::member(descriptor, 0))
            }
        }
        KIND_LIST | KIND_SET => {
            elements(slot(slots, first) as u32, slot(slots, first + 1) as u32, descriptor)
        }
        KIND_MAP => {
            entries(slot(slots, first) as u32, slot(slots, first + 1) as u32, descriptor)
        }
        other => abort(REASON_BACKEND_INVARIANT_BROKEN, descriptor, other as u64, 0),
    };
    *index = first + flats(descriptor);
    read
}

/// Room for `count` core values spilled one to a slot, every slot nothing until something is put
/// in it: a variant's case that flattens to fewer than the variant leaves the rest as nothing.
#[no_mangle]
pub unsafe extern "C" fn __souther_typed_slots(count: u32) -> u32 {
    // What the arena hands out is nothing already.
    alloc_aligned(crate::room(0, count, 8), 8)
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
        KIND_UNIT => put_case(at, descriptor, only(descriptor)),
        KIND_NEWTYPE => stored(__souther_record_get(cell, 0), descriptor::member(descriptor, 0), at),
        KIND_PRODUCT => {
            for i in 0..descriptor::arity(descriptor) {
                stored(__souther_record_get(cell, i), descriptor::member(descriptor, i), at + field(descriptor, i));
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
                put_case(at, descriptor, none(descriptor));
            } else {
                put_case(at, descriptor, some(descriptor));
                stored(value::__souther_held(cell), descriptor::member(descriptor, 0), at + payload(descriptor));
            }
        }
        _ => {
            let (pointer, length) = pointed(cell, descriptor);
            put_word(at, pointer);
            put_word(at + 4, length);
        }
    }
}

/// What a value laid out as a pointer and a length is: text, a list, a set or a map, the pointer
/// to its bytes or to the room its elements are laid out in, and how many.
///
/// The one account of it, which a value laid out in memory and a value flattened into slots both
/// take: the two differ only in where the pair is put.
unsafe fn pointed(cell: u32, descriptor: u32) -> (u32, u32) {
    match descriptor::kind(descriptor) {
        KIND_STRING => (__souther_string_bytes(cell), __souther_string_length(cell)),
        KIND_DECIMAL => copied(decimal::external(cell)),
        KIND_DATE => copied(temporal::written_day(cell)),
        KIND_TIME => copied(temporal::written_time(cell)),
        KIND_DATE_TIME => copied(temporal::written_both(cell)),
        KIND_INSTANT => copied(temporal::written_moment(cell)),
        KIND_LIST => {
            let element = descriptor::member(descriptor, 0);
            let count = __souther_list_length(cell);
            let room = room_for(count, element);
            for i in 0..count {
                stored(__souther_list_get(cell, i), element, room + i * size(element));
            }
            (room, count)
        }
        KIND_SET => {
            let element = descriptor::member(descriptor, 0);
            let count = __souther_list_length(cell);
            let room = room_for(count, element);
            let members = value::in_written_order(cell, element);
            for k in 0..count {
                stored(__souther_list_get(cell, members.place(k)), element, room + k * size(element));
            }
            (room, count)
        }
        KIND_MAP => {
            let keys = descriptor::member(descriptor, 0);
            let values = descriptor::member(descriptor, 1);
            let count = __souther_map_length(cell);
            let stride = entry_size(descriptor);
            let room = alloc_aligned(crate::room(0, count, stride), entry_alignment(descriptor));
            for i in 0..count {
                let entry = room + i * stride;
                stored(__souther_map_key(cell, i), keys, entry + key_at(descriptor));
                stored(__souther_map_value(cell, i), values, entry + value_at(descriptor));
            }
            (room, count)
        }
        other => abort(REASON_BACKEND_INVARIANT_BROKEN, descriptor, other as u64, cell as u64),
    }
}

/// Room for a list's elements, at the alignment its element is laid out at, which a caller reading
/// the list checks it begins at.
unsafe fn room_for(count: u32, element: u32) -> u32 {
    alloc_aligned(crate::room(0, count, size(element)), alignment(element))
}

/// Text a value is written as, copied out of wherever it was written into memory of its own: the
/// run text is written into is written over by the next one.
unsafe fn copied((text, length): (u32, u32)) -> (u32, u32) {
    let room = alloc_aligned(length, 1);
    core::ptr::copy_nonoverlapping(text as usize as *const u8, room as usize as *mut u8, length as usize);
    (room, length)
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
        KIND_UNIT => put_slot(slots, first, only(descriptor) as u64),
        KIND_OPTION => {
            if core::ptr::read_unaligned(cell as usize as *const u32) == TAG_NONE {
                put_slot(slots, first, none(descriptor) as u64);
            } else {
                put_slot(slots, first, some(descriptor) as u64);
                let mut inside = first + 1;
                flattened(value::__souther_held(cell), descriptor::member(descriptor, 0), slots, &mut inside);
            }
        }
        // Everything else flattens to a pointer and a length, which is what it is laid out as.
        _ => {
            let (pointer, length) = pointed(cell, descriptor);
            put_slot(slots, first, pointer as u64);
            put_slot(slots, first + 1, length as u64);
        }
    }
    *index = first + flats(descriptor);
}

/// Writes the arguments of a call out, flattened into slots or laid out as a record of them, as the
/// function the call goes to takes them. `values` is a tuple of them, in order.
#[no_mangle]
pub unsafe extern "C" fn __souther_typed_lowered(values: u32, arguments: u32, at: u32) {
    let arity = word(arguments + A_ARITY);
    let in_memory = word(arguments + A_IN_MEMORY) != 0;
    let mut index = 0;
    for i in 0..arity {
        let descriptor = word(arguments + A_ARGUMENTS + 8 * i);
        let cell = value::__souther_tuple_get(values, i);
        if in_memory {
            stored(cell, descriptor, at + word(arguments + A_ARGUMENTS + 8 * i + 4));
        } else {
            flattened(cell, descriptor, at, &mut index);
        }
    }
}

/// What a function the program reaches out to answered, as the record the compiler wrote for the
/// behavior says it lies:
///
/// ```text
/// +0  u32 the descriptor of the behavior's answer
/// +4  u32 whether it lies in memory, as the answer laid out, rather than in one slot
/// ```
const H_DESCRIPTOR: u32 = 0;
const H_IN_MEMORY: u32 = 4;

/// Reads what a function the program reaches out to answered as the behavior's type, as
/// `__souther_read_answer` reads a JSON one: a value that breaks a rule of its type is the host
/// answering outside the behavior's type, and ends the call naming the behavior and the issues.
#[no_mangle]
pub unsafe extern "C" fn __souther_typed_answer(at: u32, answer: u32, behavior: u64) -> u32 {
    issues::forget();
    let descriptor = word(answer + H_DESCRIPTOR);
    let document = if word(answer + H_IN_MEMORY) != 0 {
        loaded(at, descriptor)
    } else {
        lifted(at, &mut 0, descriptor)
    };
    let read = value::__souther_read(document, descriptor, 0, 0);
    if issues::__souther_issues_count() != 0 {
        value::refuse_answer(descriptor, behavior);
    }
    read
}

/// Where a call's typed answer is written, which the canonical ABI reads it out of before the
/// post-return gives the arena back.
unsafe fn area(result: u32) -> u32 {
    alloc_aligned(word(result + R_SIZE), word(result + R_ALIGN))
}

/// The `ok` of a call's result: what the behavior answered.
#[no_mangle]
pub unsafe extern "C" fn __souther_typed_answered(cell: u32, descriptor: u32, result: u32) -> u32 {
    let at = area(result);
    put_protocol_case(at, word(result + R_PROTOCOL), P_RESULT_WIDTH, P_OK);
    stored(cell, descriptor, at + word(result + R_PAYLOAD));
    at
}

/// The `err` of a call's result that the boundary refused: the issues found, as records.
#[no_mangle]
pub unsafe extern "C" fn __souther_typed_refused(result: u32) -> u32 {
    let at = area(result);
    let protocol = word(result + R_PROTOCOL);
    let refusal = at + word(result + R_PAYLOAD);
    put_protocol_case(at, protocol, P_RESULT_WIDTH, P_ERR);
    put_protocol_case(refusal, protocol, P_REFUSAL_WIDTH, P_ISSUES);
    let (issues, count) = laid_out_issues(protocol);
    let place = refusal + word(protocol + P_REFUSAL_PAYLOAD);
    put_word(place, issues);
    put_word(place + 4, count);
    at
}

/// The issues this call found, as a list of the records a typed caller reads.
unsafe fn laid_out_issues(protocol: u32) -> (u32, u32) {
    let count = issues::__souther_issues_count();
    let size = word(protocol + P_ISSUE_SIZE);
    let room = alloc_aligned(crate::room(0, count, size), word(protocol + P_ISSUE_ALIGN));
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
        if let Some((said, said_length)) = found.message {
            put_protocol_case(message, protocol, P_OPTION_WIDTH, P_SOME);
            put_word(message + word(protocol + P_MESSAGE_PAYLOAD), said);
            put_word(message + word(protocol + P_MESSAGE_PAYLOAD) + 4, said_length);
        }
    });
    (room, count)
}

/// The `err` of a call's result that the runtime ended: its reason, as the record holds it, and
/// for an answer a function given to the program answered outside its type, which behavior and
/// what is wrong with it.
///
/// Nothing is handed out for any reason but that one, which is not a call that ran out of room:
/// its issues were found reading an answer, and are laid out as a refused call's are.
#[no_mangle]
pub unsafe extern "C" fn __souther_typed_ended(result: u32) -> u32 {
    // Static, and not the arena's: writing why a call ended must not be able to fail, and it may
    // have ended because nothing more could be handed out. The compiler placed it for the
    // function, as long as its err reaches; one is enough, since a caller reads the result before
    // the post-return and no call begins before that.
    let at = word(result + R_ENDED);
    core::ptr::write_bytes(at as usize as *mut u8, 0, word(result + R_ENDED_LENGTH) as usize);
    let protocol = word(result + R_PROTOCOL);
    let refusal = at + word(result + R_PAYLOAD);
    put_protocol_case(at, protocol, P_RESULT_WIDTH, P_ERR);
    put_protocol_case(refusal, protocol, P_REFUSAL_WIDTH, P_ENDED);
    let ended = refusal + word(protocol + P_REFUSAL_PAYLOAD);
    let reason = core::ptr::read((crate::FAILURE + crate::OFF_REASON) as *const u32);
    put_word(ended + word(protocol + P_ENDED_REASON), reason);
    if reason == crate::REASON_INVALID_HOST_ANSWER {
        let option = ended + word(protocol + P_ENDED_INVALID);
        put_protocol_case(option, protocol, P_OPTION_WIDTH, P_SOME);
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
