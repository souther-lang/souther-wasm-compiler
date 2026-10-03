//! A Souther value while a call is running, and how one is read out of JSON and written back.
//!
//! # The cell
//!
//! Every value is a cell in the arena, whatever it is:
//!
//! ```text
//! +0  u32 tag
//! +4  what the tag makes of it: a length, a count, a slot, a descriptor, a scale, a day
//! +8  payload
//! ```
//!
//! Boxed wherever a value is kept. An `Int` in a local would be an `i64` and nothing else, but the
//! same `Int` inside a list, a map or an option has to be reachable by a pointer like everything
//! else there, and a representation that changed at the edge of a container would put a conversion
//! at every one of those edges. What a body works out on the way to a value is not kept, so the
//! compiler works arithmetic and conditions out on numbers and makes a cell only for what comes of
//! them; and a literal is a cell the compiler writes into static memory once. Both write and read
//! the layout here, which `RuntimeAbi.Cell` names on the other side.
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

use crate::decimal;
use crate::descriptor::{
    self, KIND_BOOL, KIND_DATE, KIND_DATE_TIME, KIND_DECIMAL, KIND_ENUMERATION, KIND_INSTANT,
    KIND_INT, KIND_NEWTYPE,
    KIND_LIST, KIND_MAP, KIND_OPTION, KIND_PRODUCT, KIND_SET, KIND_STRING, KIND_SUM, KIND_TIME,
    KIND_TUPLE, KIND_UNIT,
};
use crate::temporal;
use crate::order;
use crate::issues::{
    self, CODE_INVALID_FORMAT, CODE_INVALID_SIZE, CODE_NOT_ALLOWED, CODE_REQUIRED,
    CODE_TYPE_MISMATCH,
};
use crate::json;
use crate::notation;
use crate::text;
use crate::tree;
use crate::{abort, alloc, REASON_DIVISION_BY_ZERO, REASON_NOT_A_VALUE, REASON_REQUIRED_FORM_HAS_NO_PLACE};

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
/// Values written together. `+4` is how many, and the pointers follow.
///
/// Nothing names them and nothing outside the program sees one: a tuple is how a body carries two
/// things where one goes, and what crosses a boundary is a shape whose fields have names.
pub const TAG_TUPLE: u32 = 11;

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

/// A string made of pieces written one after another, each copied once.
pub unsafe fn __souther_string_of(pieces: &[&str]) -> u32 {
    let length: usize = pieces.iter().map(|piece| piece.len()).sum();
    let cell = header(TAG_STRING, length as u32);
    let mut at = alloc(length as u32) as usize;
    for piece in pieces {
        core::ptr::copy_nonoverlapping(piece.as_ptr(), at as *mut u8, piece.len());
        at += piece.len();
    }
    cell
}

/// A string of `times` copies of `text`, written straight into the cell.
pub unsafe fn __souther_string_repeated(text: &str, times: u32) -> u32 {
    let length = text.len() as u32 * times;
    let cell = header(TAG_STRING, length);
    let mut at = alloc(length) as usize;
    for _ in 0..times {
        core::ptr::copy_nonoverlapping(text.as_ptr(), at as *mut u8, text.len());
        at += text.len();
    }
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

// A list, and a set, which is a list of its members in order:
//
// ```text
// +0  u32 tag
// +4  u32 descriptor
// +8  u32 how many it holds
// +12 u32 where they are, or nothing for a set held as a tree that no reader has asked for yet
// +16 u32 the tree a set changed one member at a time is held as, or nothing
// ```
//
// Where they are is an array with two words before it: how many of its places some list holds,
// and how many places it has. A list is its length's worth of the array, and the array may hold
// more for another list made from it. So `xs ++ ys` writes `ys` after `xs` in `xs`'s own array
// where `xs` ends where the array's held places do and there is room, and is a new cell over the
// same array: `xs` still holds its own length's worth, which nothing writes to again. Where it does
// not end there, some other list was made from `xs` already, and the two are copied into an array
// of twice the room — so growing a list one element at a time takes as long as its length and not
// its square, whatever holds the list on the way. A tree is `tree`'s.
const LIST_LENGTH: u32 = 8;
const LIST_ELEMENTS: u32 = 12;
const LIST_TREE: u32 = 16;
const LIST_CELL: u32 = 20;

unsafe fn word(at: u32) -> u32 {
    core::ptr::read_unaligned(at as usize as *const u32)
}

unsafe fn put_word(at: u32, held: u32) {
    core::ptr::write_unaligned(at as usize as *mut u32, held);
}

/// An array of `room` places, of which `held` are some list's, answered as where the first is.
pub(crate) unsafe fn elements_of_room(room: u32, held: u32) -> u32 {
    let Some(bytes) = room.checked_mul(4).and_then(|places| places.checked_add(8)) else {
        abort(crate::REASON_OUT_OF_MEMORY, 0, room as u64, 0)
    };
    let at = alloc(bytes) + 8;
    put_word(at - 8, held);
    put_word(at - 4, room);
    at
}

/// How many of an array's places some list holds.
pub(crate) unsafe fn elements_held(elements: u32) -> u32 {
    word(elements - 8)
}

/// How many places an array has.
pub(crate) unsafe fn elements_room(elements: u32) -> u32 {
    word(elements - 4)
}

/// Says that some list holds that many of an array's places.
pub(crate) unsafe fn elements_now_hold(elements: u32, held: u32) {
    put_word(elements - 8, held);
}

/// A list cell over that many of an array's elements, or over a set's tree.
pub(crate) unsafe fn list_over(descriptor: u32, length: u32, elements: u32, tree: u32) -> u32 {
    let cell = alloc(LIST_CELL);
    put_word(cell, TAG_LIST);
    put_word(cell + 4, descriptor);
    put_word(cell + LIST_LENGTH, length);
    put_word(cell + LIST_ELEMENTS, elements);
    put_word(cell + LIST_TREE, tree);
    cell
}

/// A list of that many elements, with nothing in them yet.
#[no_mangle]
pub unsafe extern "C" fn __souther_list(descriptor: u32, length: u32) -> u32 {
    let cell = list_over(descriptor, length, 0, 0);
    put_word(cell + LIST_ELEMENTS, elements_of_room(length, length));
    cell
}

/// Puts a value at a position of a list, which only the one making the list does.
#[no_mangle]
pub unsafe extern "C" fn __souther_list_set(cell: u32, index: u32, value: u32) {
    put_word(word(cell + LIST_ELEMENTS) + 4 * index, value);
}

/// How many elements a list holds.
#[no_mangle]
pub unsafe extern "C" fn __souther_list_length(cell: u32) -> u32 {
    word(cell + LIST_LENGTH)
}

/// The value at a position of a list.
#[no_mangle]
pub unsafe extern "C" fn __souther_list_get(cell: u32, index: u32) -> u32 {
    word(__souther_list_elements(cell) + 4 * index)
}

/// Where a list's elements are, one word each and in order: a set held as a tree is laid out the
/// first time anything asks, and kept laid out. A walk asks once and reads the elements itself.
#[no_mangle]
pub unsafe extern "C" fn __souther_list_elements(cell: u32) -> u32 {
    let elements = word(cell + LIST_ELEMENTS);
    if elements != 0 {
        return elements;
    }
    let length = word(cell + LIST_LENGTH);
    let laid = elements_of_room(length, length);
    tree::laid_out(word(cell + LIST_TREE), laid, 4, false);
    put_word(cell + LIST_ELEMENTS, laid);
    laid
}

/// The tree a set's members stand in, made from its array the first time a member is put in or
/// taken out, and kept: a set changed many times from one is laid out as a tree once.
pub(crate) unsafe fn set_tree(cell: u32) -> u32 {
    let tree = word(cell + LIST_TREE);
    if tree != 0 || word(cell + LIST_LENGTH) == 0 {
        return tree;
    }
    let made = tree::of_ordered(word(cell + LIST_ELEMENTS), 0, word(cell + LIST_LENGTH), 4, false);
    put_word(cell + LIST_TREE, made);
    made
}

/// Whether a set is held as a tree already, so that a member is found by walking it.
pub(crate) unsafe fn held_as_tree(cell: u32) -> bool {
    word(cell + LIST_TREE) != 0
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
        KIND_DECIMAL => tag == TAG_DECIMAL,
        KIND_DATE => tag == TAG_DATE,
        KIND_TIME => tag == TAG_TIME,
        KIND_DATE_TIME => tag == TAG_DATE_TIME,
        KIND_INSTANT => tag == TAG_INSTANT,
        KIND_LIST | KIND_SET => tag == TAG_LIST,
        KIND_MAP => tag == TAG_MAP,
        KIND_OPTION => tag == TAG_SOME || tag == TAG_NONE,
        _ => core::ptr::read_unaligned((cell as usize + 4) as *const u32) == descriptor,
    })
}

/// Which of a set's alternatives a value is, as its place in what declared it.
#[no_mangle]
pub unsafe extern "C" fn __souther_case_of(cell: u32, descriptor: u32) -> u32 {
    let held = core::ptr::read_unaligned((cell as usize + 4) as *const u32);
    for i in 0..descriptor::arity(descriptor) {
        if descriptor::member(descriptor, i) == held {
            return i;
        }
    }
    abort(REASON_NOT_A_VALUE, descriptor, held as u64, cell as u64)
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
    __souther_int(__souther_int_sum(__souther_int_value(left), __souther_int_value(right)))
}

/// The `+` operator on two `Int`s a body holds as numbers rather than as cells.
///
/// What a body works out in the middle of an expression is not kept anywhere, so it is not made a
/// cell: `a + b * c` makes one cell for its answer and none for `b * c`.
#[no_mangle]
pub unsafe extern "C" fn __souther_int_sum(a: i64, b: i64) -> i64 {
    match a.checked_add(b) {
        Some(sum) => sum,
        None => abort(REASON_REQUIRED_FORM_HAS_NO_PLACE, 0, a as u64, b as u64),
    }
}

/// The `-` operator on two `Int`s held as numbers.
#[no_mangle]
pub unsafe extern "C" fn __souther_int_difference(a: i64, b: i64) -> i64 {
    match a.checked_sub(b) {
        Some(difference) => difference,
        None => abort(REASON_REQUIRED_FORM_HAS_NO_PLACE, 0, a as u64, b as u64),
    }
}

/// The `*` operator on two `Int`s held as numbers.
#[no_mangle]
pub unsafe extern "C" fn __souther_int_product(a: i64, b: i64) -> i64 {
    match a.checked_mul(b) {
        Some(product) => product,
        None => abort(REASON_REQUIRED_FORM_HAS_NO_PLACE, 0, a as u64, b as u64),
    }
}

/// The unary `-` on `Int`. Total, unlike `+`/`-`/`*` (spec: `Core.Neg` is `AbortSet.NONE`, not
/// `REQUIRED_FORM_HAS_NO_PLACE` — traced against `souther.compiler.abort.AbortSites` and
/// `souther.compiler.codegen.BodyGen`, which emits this as bytecode's own `lneg`): `MIN_VALUE` is
/// the one `Int` whose negation is not representable as a positive `Int`, but two's-complement
/// negation of it wraps back to `MIN_VALUE` rather than raising, on the JVM and here alike, so
/// `wrapping_neg` and not `checked_neg` is this operator's actual, total arithmetic.
#[no_mangle]
pub unsafe extern "C" fn __souther_negate(cell: u32) -> u32 {
    __souther_int(__souther_int_value(cell).wrapping_neg())
}

/// The `-` operator on `Int`.
#[no_mangle]
pub unsafe extern "C" fn __souther_subtract(left: u32, right: u32) -> u32 {
    __souther_int(__souther_int_difference(__souther_int_value(left), __souther_int_value(right)))
}

/// The `*` operator on `Int`.
#[no_mangle]
pub unsafe extern "C" fn __souther_multiply(left: u32, right: u32) -> u32 {
    __souther_int(__souther_int_product(__souther_int_value(left), __souther_int_value(right)))
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
        None => abort(REASON_REQUIRED_FORM_HAS_NO_PLACE, 0, a as u64, b as u64),
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

/// Two strings joined, canonicalized. Each side is NFC, but NFC is not closed under joining: a
/// letter followed by a combining mark composes into one code point at the seam.
///
/// Not an entry of its own: `++` on strings is `String.append`, and reaches this through
/// `__souther_string_append` like any other call of it.
pub unsafe fn joined(left: u32, right: u32) -> u32 {
    if __souther_string_length(right) == 0 {
        return left;
    }
    if __souther_string_length(left) == 0 {
        return right;
    }
    notation::joined(&[notation::str_of(left), notation::str_of(right)])
}

// A map:
//
// ```text
// +0  u32 tag
// +4  u32 descriptor
// +8  u32 how many entries it holds
// +12 u32 where they are, a key and its value per entry in the order of the keys, or nothing for a
//         map held as a tree that no reader has asked for yet
// +16 u32 the tree a map changed one entry at a time is held as, or nothing
// ```
//
// As a set is: changed by putting an entry in or taking one out, a map is a tree that shares all
// but a path with the one it was made from; read entry by entry, it is laid out once.
const MAP_ENTRIES: u32 = 12;
const MAP_TREE: u32 = 16;

/// A map cell over that many entries laid out at `entries`, or over a tree.
pub(crate) unsafe fn map_over(descriptor: u32, length: u32, entries: u32, tree: u32) -> u32 {
    let cell = alloc(LIST_CELL);
    put_word(cell, TAG_MAP);
    put_word(cell + 4, descriptor);
    put_word(cell + LIST_LENGTH, length);
    put_word(cell + MAP_ENTRIES, entries);
    put_word(cell + MAP_TREE, tree);
    cell
}

/// A map of that many entries, with nothing in them yet.
#[no_mangle]
pub unsafe extern "C" fn __souther_map(descriptor: u32, entries: u32) -> u32 {
    let Some(bytes) = entries.checked_mul(8) else {
        abort(crate::REASON_OUT_OF_MEMORY, 0, entries as u64, 0)
    };
    map_over(descriptor, entries, alloc(bytes), 0)
}

/// The element at a place of a list's elements, for a walk over them that asked where they are
/// once (`__souther_list_elements`) rather than at every one.
#[inline(always)]
pub(crate) unsafe fn element_at(elements: u32, index: u32) -> u32 {
    word(elements + 4 * index)
}

/// Puts an element at a place of a list's elements, which only the one making the list does.
#[inline(always)]
pub(crate) unsafe fn put_element_at(elements: u32, index: u32, held: u32) {
    put_word(elements + 4 * index, held);
}

/// The key of an entry of a map's entries, for a walk that asked where they are once.
#[inline(always)]
pub(crate) unsafe fn entry_key(entries: u32, index: u32) -> u32 {
    word(entries + 8 * index)
}

/// The value of an entry of a map's entries, for a walk that asked where they are once.
#[inline(always)]
pub(crate) unsafe fn entry_value(entries: u32, index: u32) -> u32 {
    word(entries + 8 * index + 4)
}

/// Puts an entry at a place of a map's entries, which only the one making the map does.
#[inline(always)]
pub(crate) unsafe fn put_entry(entries: u32, index: u32, key: u32, held: u32) {
    put_word(entries + 8 * index, key);
    put_word(entries + 8 * index + 4, held);
}

/// Where a map's entries are, laid out from its tree the first time anything asks.
pub(crate) unsafe fn map_entries(cell: u32) -> u32 {
    let entries = word(cell + MAP_ENTRIES);
    if entries != 0 || word(cell + LIST_LENGTH) == 0 {
        return entries;
    }
    let laid = alloc(8 * word(cell + LIST_LENGTH));
    tree::laid_out(word(cell + MAP_TREE), laid, 8, true);
    put_word(cell + MAP_ENTRIES, laid);
    laid
}

/// The tree a map's entries stand in, made from its entries the first time one is put in or taken
/// out, and kept.
pub(crate) unsafe fn map_tree(cell: u32) -> u32 {
    let tree = word(cell + MAP_TREE);
    if tree != 0 || word(cell + LIST_LENGTH) == 0 {
        return tree;
    }
    let made = tree::of_ordered(word(cell + MAP_ENTRIES), 0, word(cell + LIST_LENGTH), 8, true);
    put_word(cell + MAP_TREE, made);
    made
}

/// Whether a map is held as a tree already, so that a key is found by walking it.
pub(crate) unsafe fn map_held_as_tree(cell: u32) -> bool {
    word(cell + MAP_TREE) != 0
}

/// What a map's keys are, for a caller that has the map and not the type it was declared as.
pub(crate) unsafe fn map_keys(cell: u32) -> u32 {
    descriptor::member(core::ptr::read_unaligned((cell as usize + 4) as *const u32), 0)
}

/// How many entries a map holds.
///
/// Every reader of a map's entries asks this first, so this is where a cell that is not a map is
/// stopped: a map a walk is growing is laid out otherwise, and only the readers a walk's step may
/// call are handed one, each of which asks for it by its own tag first.
#[no_mangle]
pub unsafe extern "C" fn __souther_map_length(cell: u32) -> u32 {
    let tag = core::ptr::read_unaligned(cell as usize as *const u32);
    if tag != TAG_MAP {
        abort(REASON_NOT_A_VALUE, 0, tag as u64, cell as u64);
    }
    core::ptr::read_unaligned((cell as usize + HEADER) as *const u32)
}

/// The key of one of a map's entries.
#[no_mangle]
pub unsafe extern "C" fn __souther_map_key(cell: u32, index: u32) -> u32 {
    word(map_entries(cell) + 8 * index)
}

/// The value of one of a map's entries.
#[no_mangle]
pub unsafe extern "C" fn __souther_map_value(cell: u32, index: u32) -> u32 {
    word(map_entries(cell) + 8 * index + 4)
}

/// Puts an entry at a position of a map, which only the one making the map does.
#[no_mangle]
pub unsafe extern "C" fn __souther_map_set(cell: u32, index: u32, key: u32, value: u32) {
    let entries = word(cell + MAP_ENTRIES);
    put_word(entries + 8 * index, key);
    put_word(entries + 8 * index + 4, value);
}

/// Shortens a map to the entries it kept.
pub(crate) unsafe fn map_of_length(cell: u32, entries: u32) {
    core::ptr::write_unaligned((cell as usize + HEADER) as *mut u32, entries);
}

/// Starts the array a call's arguments are written as.
///
/// A crossing out of this module writes its arguments the way a caller writes them coming in, so
/// what a host implements is what a host calls: a document in, a document out.
#[no_mangle]
pub unsafe extern "C" fn __souther_arguments(held: u32) -> u32 {
    let cell = header(TAG_ARGUMENTS, 0);
    let _ = alloc(4);
    core::ptr::write_unaligned((cell as usize + HEADER) as *mut u32, held);
    text::begin();
    write(b"[");
    cell
}

/// Writes one more argument into the array being written.
#[no_mangle]
pub unsafe extern "C" fn __souther_argument_written(document: u32, value: u32, descriptor: u32) {
    let left = core::ptr::read_unaligned((document as usize + HEADER) as *const u32);
    let held = core::ptr::read_unaligned((document as usize + 4) as *const u32);
    let _ = held;
    if left == 0 {
        return;
    }
    written(value, descriptor);
    core::ptr::write_unaligned((document as usize + HEADER) as *mut u32, left - 1);
    if left > 1 {
        write(b",");
    }
}

/// Closes the array, answering the run of bytes it is.
#[no_mangle]
pub unsafe extern "C" fn __souther_arguments_sealed(document: u32) -> u32 {
    write(b"]");
    let (at, length) = text::ended();
    core::ptr::write_unaligned((document as usize + 4) as *mut u32, at);
    core::ptr::write_unaligned((document as usize + HEADER) as *mut u32, length);
    document
}

/// Where a written array of arguments starts.
#[no_mangle]
pub unsafe extern "C" fn __souther_arguments_bytes(document: u32) -> u32 {
    core::ptr::read_unaligned((document as usize + 4) as *const u32)
}

/// How long a written array of arguments is.
#[no_mangle]
pub unsafe extern "C" fn __souther_arguments_length(document: u32) -> u32 {
    core::ptr::read_unaligned((document as usize + HEADER) as *const u32)
}

/// An array of arguments being written. `+4` is where its bytes start.
pub const TAG_ARGUMENTS: u32 = 12;
/// An amount and how it was written. See `decimal` for what it holds.
pub const TAG_DECIMAL: u32 = 13;
/// A day. See `temporal` for what it holds.
pub const TAG_DATE: u32 = 14;
/// A time of day.
pub const TAG_TIME: u32 = 15;
/// A day and a time of day.
pub const TAG_DATE_TIME: u32 = 16;

/// A moment on the timeline.
pub const TAG_INSTANT: u32 = 17;

/// A map a walk is growing. Not a map: its entries stand in the order they were put in, found by a
/// table of their keys' hashes, so a reader taking it for a map would read past what is there. See
/// `kernel::__souther_map_builder` for its layout.
pub const TAG_MAP_BUILDER: u32 = 18;

/// Values written together, with nothing in them yet.
#[no_mangle]
pub unsafe extern "C" fn __souther_tuple(held: u32) -> u32 {
    let cell = header(TAG_TUPLE, held);
    let _ = alloc(4 * held);
    cell
}

/// Puts a value at a place of a tuple.
#[no_mangle]
pub unsafe extern "C" fn __souther_tuple_set(cell: u32, index: u32, value: u32) {
    core::ptr::write_unaligned((cell as usize + HEADER + 4 * index as usize) as *mut u32, value);
}

/// What a tuple holds at a place.
#[no_mangle]
pub unsafe extern "C" fn __souther_tuple_get(cell: u32, index: u32) -> u32 {
    core::ptr::read_unaligned((cell as usize + HEADER + 4 * index as usize) as *const u32)
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
    // How many it holds, how many it has room for, and the room: three things and not two. The
    // arena hands out exactly what is asked for, so a cell asked for too little ends where the
    // next one begins, and the last place written is the next cell's first word.
    let _ = alloc(8 + 4 * INITIAL_ROOM);
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

/// Adds one value to the end of a builder, answering the builder that holds it.
///
/// What a step writing `acc ++ [x]` comes to: the one value, without the list of one it was
/// written in.
#[no_mangle]
pub unsafe extern "C" fn __souther_grow_one(builder: u32, value: u32) -> u32 {
    grown(builder, value)
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
    let _ = alloc(8 + 4 * (room * 2 + 1));
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
        mismatch(0, 0, tag, b"array");
        return;
    }
    let held = json::__souther_json_length(document);
    if held != expected {
        issues::meta::begin();
        issues::meta::integer(b"actual", held as i64);
        issues::meta::integer(b"expected", expected as i64);
        issues::issue(CODE_INVALID_SIZE, 0, 0, issues::meta::end());
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
        KIND_DECIMAL => amount(value, path, path_length),
        KIND_DATE | KIND_TIME | KIND_DATE_TIME | KIND_INSTANT => {
            when(value, descriptor::kind(descriptor), path, path_length)
        }
        KIND_UNIT => unit(value, descriptor, path, path_length),
        KIND_PRODUCT => product(value, descriptor, path, path_length),
        KIND_NEWTYPE => named_for(value, descriptor, path, path_length),
        KIND_SUM => sum(value, descriptor, path, path_length),
        KIND_ENUMERATION => enumeration(value, descriptor, path, path_length),
        // A tuple is how a body carries two things where one goes. Nothing outside the program is
        // shown one, so nothing outside writes one either.
        KIND_TUPLE => abort(REASON_NOT_A_VALUE, descriptor, KIND_TUPLE as u64, 0),
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
        mismatch(path, path_length, tag, b"long");
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
            mismatch(path, path_length, tag, b"long");
            return 0;
        }
        magnitude = magnitude * 10 + (digit - b'0') as u128;
        if magnitude > 1u128 << 63 {
            wider_than_long(path, path_length);
            return 0;
        }
        at += 1;
    }
    let limit = if negative { 1u128 << 63 } else { i64::MAX as u128 };
    if magnitude > limit {
        wider_than_long(path, path_length);
        return 0;
    }
    if negative {
        __souther_int((magnitude as i128).wrapping_neg() as i64)
    } else {
        __souther_int(magnitude as i64)
    }
}

/// A whole number written wider than an `Int` holds, which Raoh's reader of one says as a mismatch
/// of its range.
unsafe fn wider_than_long(path: u32, path_length: u32) {
    issues::meta::begin();
    issues::meta::word(b"expected", b"long");
    issues::keyed(CODE_TYPE_MISMATCH, issues::KEY_NUMERIC_RANGE, path, path_length,
        issues::meta::end());
}

unsafe fn boolean(value: u32, path: u32, path_length: u32) -> u32 {
    match json::__souther_json_tag(value) {
        json::TAG_TRUE => __souther_bool(1),
        json::TAG_FALSE => __souther_bool(0),
        other => {
            mismatch(path, path_length, other, b"boolean");
            0
        }
    }
}

/// A string is let in as the canonical `String` it is. Text that is not one — bytes that are not
/// UTF-8, or a canonical form longer than a `String` holds — is a string that denotes no `String`,
/// which is the format being wrong rather than the type.
unsafe fn text(value: u32, path: u32, path_length: u32) -> u32 {
    let tag = json::__souther_json_tag(value);
    if tag != json::TAG_STRING {
        mismatch(path, path_length, tag, b"string");
        return 0;
    }
    match notation::admitted(json::__souther_json_bytes(value), json::__souther_json_length(value)) {
        Some(held) => held,
        None => {
            issues::issue(CODE_INVALID_FORMAT, path, path_length, issues::meta::none());
            0
        }
    }
}

/// A number is read as the amount it names, keeping the digits it was written with.
unsafe fn amount(value: u32, path: u32, path_length: u32) -> u32 {
    let tag = json::__souther_json_tag(value);
    if tag != json::TAG_NUMBER {
        mismatch(path, path_length, tag, b"number");
        return 0;
    }
    let held = decimal::parse(
        json::__souther_json_bytes(value),
        json::__souther_json_length(value),
    );
    if held == 0 {
        mismatch(path, path_length, tag, b"number");
        return 0;
    }
    held
}

/// A day, a time of day, or the two together, read as a calendar and a clock write them.
unsafe fn when(value: u32, kind: u32, path: u32, path_length: u32) -> u32 {
    let tag = json::__souther_json_tag(value);
    // A temporal is written as text, and text that is no reading of the calendar or the clock is the
    // format being wrong (spec §decoder-error).
    if tag != json::TAG_STRING {
        mismatch(path, path_length, tag, b"string");
        return 0;
    }
    let at = json::__souther_json_bytes(value);
    let length = json::__souther_json_length(value);
    if kind == KIND_INSTANT {
        return match temporal::read_moment(at, length) {
            Some((second, nano)) => temporal::moment_made(second, nano),
            None => {
                not_written_as(kind, path, path_length);
                0
            }
        };
    }
    let held = match kind {
        KIND_DATE => temporal::read_day(at, length).map(|d| (TAG_DATE, d, 0)),
        KIND_TIME => temporal::read_time(at, length).map(|s| (TAG_TIME, 0, s)),
        _ => temporal::read_both(at, length).map(|(d, s)| (TAG_DATE_TIME, d, s)),
    };
    match held {
        Some((tag, day, second)) => temporal::made(tag, day, second),
        None => {
            not_written_as(kind, path, path_length);
            0
        }
    }
}

/// Text that is no reading of the calendar or the clock, said with the form the temporal is written
/// in — the language's words for it (spec §temporal-text), which the JVM's decoder says too and the
/// catalog's template for `invalid_format` would say less than.
unsafe fn not_written_as(kind: u32, path: u32, path_length: u32) {
    let said: &[u8] = match kind {
        KIND_DATE => b"is not a Date written as yyyy-MM-dd, its year signed outside 0000 to 9999",
        KIND_TIME => b"is not a Time written as HH:mm or HH:mm:ss",
        KIND_INSTANT => b"is not an Instant written as yyyy-MM-ddTHH:mm:ss with an offset, its year \
            signed outside 0000 to 9999",
        _ => b"is not a DateTime written as yyyy-MM-ddTHH:mm or yyyy-MM-ddTHH:mm:ss, its year signed \
            outside 0000 to 9999",
    };
    issues::said(CODE_INVALID_FORMAT, CODE_INVALID_FORMAT, path, path_length, issues::meta::none(),
        said);
}

/// A type with one value is written as an empty object: there is nothing to say about which one it
/// is, and a document that says something is saying something the type has no room for.
unsafe fn unit(value: u32, descriptor: u32, path: u32, path_length: u32) -> u32 {
    let tag = json::__souther_json_tag(value);
    if tag != json::TAG_OBJECT {
        mismatch(path, path_length, tag, b"object");
        return 0;
    }
    __souther_unit(descriptor)
}

unsafe fn product(value: u32, descriptor: u32, path: u32, path_length: u32) -> u32 {
    let tag = json::__souther_json_tag(value);
    if tag != json::TAG_OBJECT {
        mismatch(path, path_length, tag, b"object");
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
            required(at, at_length);
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
        crate::clauses::broken(cell, descriptor, clause as u32, path, path_length);
        return 0;
    }
    cell
}

/// A name for a value of another type: what that type is written as, held under this name.
///
/// The value crosses as the type it is a name for, so what is read is the field's own descriptor
/// and there is no object here. What it is held as is a value of one field, the same as a product
/// of one — construction, access and the clauses that must hold are the same for the two, and only
/// the writing differs.
unsafe fn named_for(value: u32, descriptor: u32, path: u32, path_length: u32) -> u32 {
    let read = __souther_read(value, descriptor::member(descriptor, 0), path, path_length);
    if read == 0 {
        return 0;
    }
    let cell = __souther_record(descriptor);
    __souther_record_set(cell, 0, read);
    // Reported where the value is, and not below it at a field nobody wrote: a name for a value is
    // written as that value, so the position a caller would look at is this one.
    let clause = __souther_check_invariants(cell, descriptor);
    if clause >= 0 {
        crate::clauses::broken(cell, descriptor, clause as u32, path, path_length);
        return 0;
    }
    cell
}

/// A field of a record, found by the name it goes by rather than by where it lies.
///
/// A field every case of a set of alternatives spreads is read off the set, and which case a value
/// turned out to be is not known until there is a value — so where in it the field lies is not
/// known either, and the two cases need not put it in the same place. The value carries the
/// descriptor of what it was made as, and that descriptor names its fields, so this asks it.
#[no_mangle]
pub unsafe extern "C" fn __souther_record_named(cell: u32, at: u32, length: u32) -> u32 {
    let descriptor = core::ptr::read_unaligned((cell as usize + 4) as *const u32);
    for i in 0..descriptor::arity(descriptor) {
        let (field, field_length) = descriptor::name(descriptor, i);
        if same(field, field_length, at, length) {
            return __souther_record_get(cell, i);
        }
    }
    // Nothing a caller wrote reaches this. A field read off a set of alternatives is one every
    // case of it has, which the check settled before this compiler wrote the read.
    abort(REASON_NOT_A_VALUE, descriptor, at as u64, length as u64)
}

/// Which of a type's invariants a value breaks, or minus one where it breaks none.
///
/// The check is generated: what must hold of a value is written in Souther, so what runs it is a
/// body this runtime knows nothing about, reached through the module's table.
#[no_mangle]
pub unsafe extern "C" fn __souther_check_invariants(cell: u32, descriptor: u32) -> i32 {
    let kind = descriptor::kind(descriptor);
    if kind != KIND_PRODUCT && kind != KIND_NEWTYPE {
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
        mismatch(path, path_length, tag, b"array");
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
/// A list in the order its type places its elements, in place.
unsafe fn sorted_in_place(cell: u32, element: u32) {
    let held = __souther_list_length(cell);
    if held < 2 {
        return;
    }
    let room = alloc(4 * held);
    let elements = __souther_list_elements(cell);
    let mut width = 1;
    while width < held {
        let mut at = 0;
        while at < held {
            let middle = if at + width < held { at + width } else { held };
            let end = if at + 2 * width < held { at + 2 * width } else { held };
            let mut left = at;
            let mut right = middle;
            let mut into = at;
            while into < end {
                let take_left = if left == middle {
                    false
                } else if right == end {
                    true
                } else {
                    order::compare(
                        element_at(elements, left),
                        element_at(elements, right),
                        element,
                    ) <= 0
                };
                let taken = if take_left {
                    left += 1;
                    left - 1
                } else {
                    right += 1;
                    right - 1
                };
                core::ptr::write_unaligned(
                    (room + into * 4) as *mut u32,
                    element_at(elements, taken),
                );
                into += 1;
            }
            at += 2 * width;
        }
        for i in 0..held {
            put_element_at(elements, i, core::ptr::read_unaligned((room + i * 4) as *const u32));
        }
        width *= 2;
    }
}

/// Sorted here rather than on the way out because what a set is does not depend on how it was
/// written: two documents listing the same members are one set, and a set that only settled its
/// order at the boundary would compare as two.
pub(crate) unsafe fn sorted_and_deduplicated(cell: u32, descriptor: u32) -> u32 {
    let element = descriptor::member(descriptor, 0);
    let held = __souther_list_length(cell);
    // Merged in runs that double: a set is written out by hand and is usually small, but usually is
    // not a bound, and a set twice as long would otherwise cost four times as much to settle.
    sorted_in_place(cell, element);
    let elements = __souther_list_elements(cell);
    let mut kept = 0;
    for i in 0..held {
        let each = element_at(elements, i);
        if kept == 0
            || order::compare(element_at(elements, kept - 1), each, element) != 0
        {
            put_element_at(elements, kept, each);
            kept += 1;
        }
    }
    let out = __souther_list(descriptor, kept);
    for i in 0..kept {
        __souther_list_set(out, i, element_at(elements, i));
    }
    out
}

/// The text a key of a map is written as, without the quotes a document puts round it.
///
/// A map's external form is an object, whose member names are strings, so a type keys a map
/// exactly when it is written as a bare string — and every one that is is written the same way in
/// key position as anywhere else. So this is not a second account of what a value is written as:
/// it is the same one, read back for the two things a key is used for, standing in an order and
/// standing for one entry.
pub(crate) unsafe fn key_text(cell: u32, descriptor: u32) -> (u32, u32) {
    if descriptor::kind(descriptor) == KIND_NEWTYPE {
        return key_text(__souther_record_get(cell, 0), descriptor::member(descriptor, 0));
    }
    match descriptor::kind(descriptor) {
        KIND_STRING => (__souther_string_bytes(cell), __souther_string_length(cell)),
        KIND_DATE => temporal::written_day(cell),
        KIND_TIME => temporal::written_time(cell),
        KIND_DATE_TIME => temporal::written_both(cell),
        KIND_INSTANT => temporal::written_moment(cell),
        KIND_ENUMERATION => {
            let held = core::ptr::read_unaligned((cell as usize + 4) as *const u32);
            for i in 0..descriptor::arity(descriptor) {
                if descriptor::member(descriptor, i) == held {
                    return descriptor::name(descriptor, i);
                }
            }
            abort(REASON_NOT_A_VALUE, descriptor, held as u64, cell as u64)
        }
        other => abort(REASON_NOT_A_VALUE, descriptor, other as u64, cell as u64),
    }
}

/// Where one key of a map stands relative to another, and whether they are one key.
///
/// Two keys are one key where `==` says they are one value (ADR-0009), which is `order::ranked`
/// answering nothing between them. A key a map can cross with is written as text, and a map is
/// written in the order its keys' texts sort, so such a key stands in that order here as well —
/// and two of them are one text exactly where they are one value. Any other key is a key of a map a
/// body holds and no boundary writes, and stands where `ranked` puts it.
///
/// A key written as text is compared from what it holds where that is the order of its text, so
/// that finding an entry does not write a key out per comparison. A time of day is written as two
/// digits per part, the seconds left off where there are none, so its text and its number stand in
/// one order. A day is written that way while its year has four digits; a year before the first or
/// past the ten thousandth is written with a sign and more digits, which is not the order of the
/// days, so such a key is compared as text.
pub(crate) unsafe fn key_order(left: u32, right: u32, descriptor: u32) -> i32 {
    match descriptor::kind(descriptor) {
        KIND_NEWTYPE => key_order(
            __souther_record_get(left, 0),
            __souther_record_get(right, 0),
            descriptor::member(descriptor, 0),
        ),
        KIND_STRING => order::compare_runs(
            __souther_string_bytes(left),
            __souther_string_length(left),
            __souther_string_bytes(right),
            __souther_string_length(right),
        ),
        KIND_TIME => sign_of(temporal::second(left) as i64 - temporal::second(right) as i64),
        KIND_DATE | KIND_DATE_TIME
            if temporal::in_four_digit_years(left) && temporal::in_four_digit_years(right) =>
        {
            let by_day = sign_of(temporal::day(left) as i64 - temporal::day(right) as i64);
            if by_day != 0 {
                return by_day;
            }
            sign_of(temporal::second(left) as i64 - temporal::second(right) as i64)
        }
        KIND_DATE | KIND_DATE_TIME | KIND_INSTANT | KIND_ENUMERATION => {
            let (a, a_length) = key_text(left, descriptor);
            let (b, b_length) = key_text(right, descriptor);
            order::compare_runs(a, a_length, b, b_length)
        }
        _ => order::ranked(left, right, descriptor),
    }
}

fn sign_of(difference: i64) -> i32 {
    match difference {
        d if d < 0 => -1,
        0 => 0,
        _ => 1,
    }
}

/// A map is written as an object, its keys the keys and its entries in ascending order of them.
///
/// A key written twice names one entry, and the one that stands is the last written: what reaches
/// a decoder is what the document says at that key, and a document says it last.
///
/// Read, then sorted, then collapsed — three walks rather than one. Filing each entry against the
/// ones already kept as it is read costs a comparison for every pair of members, and an object is
/// as long as whoever sent it wanted.
unsafe fn map(value: u32, descriptor: u32, path: u32, path_length: u32) -> u32 {
    let tag = json::__souther_json_tag(value);
    if tag != json::TAG_OBJECT {
        mismatch(path, path_length, tag, b"object");
        return 0;
    }
    let held = json::__souther_json_length(value);
    let cell = __souther_map(descriptor, held);
    let keys = descriptor::member(descriptor, 0);
    let values = descriptor::member(descriptor, 1);
    let mut whole = true;
    let mut read_in = 0;
    for i in 0..held {
        let written = json::__souther_json_key(value, i);
        let name = json::__souther_json_bytes(written);
        let name_length = json::__souther_json_length(written);
        let (at, at_length) = below(path, path_length, (name, name_length));
        let read = __souther_read(json::__souther_json_value(value, i), values, at, at_length);
        if read == 0 {
            whole = false;
        }
        let key = __souther_read(written, keys, at, at_length);
        if key == 0 {
            // A member name that is no key of this type leaves nothing to file the entry under,
            // so the entry is dropped and the whole is already not answered for.
            whole = false;
            continue;
        }
        __souther_map_set(cell, read_in, key, read);
        read_in += 1;
    }
    map_of_length(cell, read_in);
    if !whole {
        return 0;
    }
    sorted_by_key(cell, keys);
    map_of_length(cell, collapsed(cell, keys));
    cell
}

/// The entries with each run of one key left as its last, answering how many are left.
///
/// After the sort, so two member names that spell one key stand next to each other. Which of them
/// stands is the one the document wrote last, and the sort leaves a run in the order it was
/// written.
unsafe fn collapsed(cell: u32, keys: u32) -> u32 {
    let held = __souther_map_length(cell);
    let mut kept = 0;
    for i in 0..held {
        let last = i + 1 == held
            || key_order(__souther_map_key(cell, i), __souther_map_key(cell, i + 1), keys) != 0;
        if last {
            __souther_map_set(cell, kept, __souther_map_key(cell, i), __souther_map_value(cell, i));
            kept += 1;
        }
    }
    kept
}

/// A map's entries, ascending by what its keys are written as.
///
/// By the written form and not by where a key stands: a set of alternatives places its own in the
/// order the declaration writes them, and a document's members are in the order their names sort.
///
/// Merged in runs that double, which keeps two entries of one key in the order they were written —
/// what the collapse after this leans on — and reads each entry a number of times that grows with
/// the logarithm of how many there are rather than with how many there are.
pub(crate) unsafe fn sorted_by_key(cell: u32, keys: u32) {
    let held = __souther_map_length(cell);
    if held < 2 {
        return;
    }
    let room = alloc(8 * held);
    let entries = map_entries(cell);
    let mut width = 1;
    while width < held {
        let mut at = 0;
        while at < held {
            let middle = if at + width < held { at + width } else { held };
            let end = if at + 2 * width < held { at + 2 * width } else { held };
            merged(cell, keys, room, at, middle, end);
            at += 2 * width;
        }
        for i in 0..held {
            put_entry(entries, i,
                core::ptr::read_unaligned((room + i * 8) as *const u32),
                core::ptr::read_unaligned((room + i * 8 + 4) as *const u32),
            );
        }
        width *= 2;
    }
}

/// Two runs of entries laid into `room` as one, the earlier one first where their keys agree.
unsafe fn merged(cell: u32, keys: u32, room: u32, from: u32, middle: u32, end: u32) {
    let entries = map_entries(cell);
    let mut left = from;
    let mut right = middle;
    let mut at = from;
    while at < end {
        let take_left = if left == middle {
            false
        } else if right == end {
            true
        } else {
            key_order(entry_key(entries, left), entry_key(entries, right), keys) <= 0
        };
        let taken = if take_left {
            left += 1;
            left - 1
        } else {
            right += 1;
            right - 1
        };
        core::ptr::write_unaligned((room + at * 8) as *mut u32, entry_key(entries, taken));
        core::ptr::write_unaligned(
            (room + at * 8 + 4) as *mut u32,
            entry_value(entries, taken),
        );
        at += 1;
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
        mismatch(path, path_length, tag, b"string");
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
    // A name no case goes by is text of a format the set does not take, said with the set it is
    // not one of, as the JVM's reader of an enumeration says it.
    issues::meta::begin();
    let (named, named_length) = descriptor::enumeration_name(descriptor);
    issues::meta::text(b"type", named, named_length);
    issues::issue(CODE_INVALID_FORMAT, path, path_length, issues::meta::end());
    0
}

/// A value of a sum is written as its case, with the case's name under `type`.
unsafe fn sum(value: u32, descriptor: u32, path: u32, path_length: u32) -> u32 {
    let tag = json::__souther_json_tag(value);
    if tag != json::TAG_OBJECT {
        mismatch(path, path_length, tag, b"object");
        return 0;
    }
    let written = entry(value, DISCRIMINATOR.as_ptr() as u32, DISCRIMINATOR.len() as u32);
    if written == 0 {
        let (at, at_length) = below(path, path_length, discriminator());
        required(at, at_length);
        return 0;
    }
    if json::__souther_json_tag(written) != json::TAG_STRING {
        let (at, at_length) = below(path, path_length, discriminator());
        mismatch(at, at_length, json::__souther_json_tag(written), b"string");
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
    // A tag naming no case is not one of those the sum allows, and which those are is said.
    let (at, at_length) = below(path, path_length, discriminator());
    issues::meta::begin();
    issues::meta::texts(
        b"allowed",
        (0..descriptor::arity(descriptor)).map(|i| descriptor::name(descriptor, i)),
    );
    issues::issue(CODE_NOT_ALLOWED, at, at_length, issues::meta::end());
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
    text::begin();
    text::put(b"{\"value\":");
    written(cell, descriptor);
    text::put(b"}");
    let (at, length) = text::ended();
    json::packed(at, length)
}

pub(crate) unsafe fn written(cell: u32, descriptor: u32) {
    match descriptor::kind(descriptor) {
        KIND_INT => copied(json::__souther_json_write_int(__souther_int_value(cell))),
        KIND_BOOL => copied(json::__souther_json_write_bool(__souther_bool_value(cell))),
        KIND_STRING => copied(json::__souther_json_write_string(
            __souther_string_bytes(cell),
            __souther_string_length(cell),
        )),
        KIND_DECIMAL => {
            // The one form of the amount, so that two ways of writing it are one document.
            let (at, length) = decimal::written(decimal::canonical(cell));
            text::push(at, length);
        }
        KIND_DATE | KIND_TIME | KIND_DATE_TIME | KIND_INSTANT => {
            let (at, length) = match descriptor::kind(descriptor) {
                KIND_DATE => temporal::written_day(cell),
                KIND_TIME => temporal::written_time(cell),
                KIND_INSTANT => temporal::written_moment(cell),
                _ => temporal::written_both(cell),
            };
            write(b"\"");
            text::push(at, length);
            write(b"\"");
        }
        KIND_UNIT => write(b"{}"),
        KIND_PRODUCT => fields(cell, descriptor, false),
        // Written as the type it is a name for is written. The name is what a model reads it by
        // and what a clause is about; it is not part of the value that crosses.
        KIND_NEWTYPE => written(__souther_record_get(cell, 0), descriptor::member(descriptor, 0)),
        KIND_SUM => tagged(cell, descriptor),
        KIND_ENUMERATION => named(cell, descriptor),
        KIND_TUPLE => abort(REASON_NOT_A_VALUE, descriptor, KIND_TUPLE as u64, cell as u64),
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
            let keys = descriptor::member(descriptor, 0);
            let values = descriptor::member(descriptor, 1);
            for i in 0..__souther_map_length(cell) {
                if i > 0 {
                    write(b",");
                }
                let (spelt, spelt_length) = key_text(__souther_map_key(cell, i), keys);
                copied(json::__souther_json_write_string(spelt, spelt_length));
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
            copied(json::__souther_json_write_string(tag, tag_length));
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
            copied(json::__souther_json_write_string(tag, tag_length));
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
        copied(json::__souther_json_write_string(field, field_length));
        write(b":");
        written(held, member);
    }
    write(b"}");
}

unsafe fn write(bytes: &[u8]) {
    text::put(bytes);
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

/// Adds to the run what a writer answered as a pointer and a length.
unsafe fn copied(answer: u64) {
    text::push(answer as u32, (answer >> 32) as u32);
}

/// What a JSON value is, in the words an issue reports it with.
/// Something other than what a place was declared to hold, as Raoh's readers say it: nothing at
/// all, `null` included, is `required`; anything else is a `type_mismatch` naming the kind found
/// and the kind the reader takes (spec §decoder-error).
///
/// `expected` is the word Raoh's reader of the declared type says it takes — `long` for an `Int`,
/// `number` for a `Decimal`, `string` for text and for a temporal, `object` for a shape — so a
/// resolver that writes sentences for one backend's issues writes them for this one's.
unsafe fn mismatch(path: u32, path_length: u32, tag: u32, expected: &[u8]) {
    if tag == json::TAG_NULL {
        required(path, path_length);
        return;
    }
    issues::meta::begin();
    issues::meta::word(b"actual", kind_of(tag));
    issues::meta::word(b"expected", expected);
    issues::issue(CODE_TYPE_MISMATCH, path, path_length, issues::meta::end());
}

/// Nothing where something is required.
unsafe fn required(path: u32, path_length: u32) {
    issues::issue(CODE_REQUIRED, path, path_length, issues::meta::none());
}

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
