//! The operations the standard library declares as intrinsic.
//!
//! Each takes its arguments in the order the library's own signature writes them, so a call site
//! hands over what the call held and nothing rearranges anything on the way.
//!
//! What each one means belongs to Souther. These are written against `souther.runtime`'s own
//! account of it — a `String` is measured and cut in code points, a whole number that leaves the
//! range ends the call, a count no string could reach ends it rather than quietly making fewer
//! copies than were asked for — and the tests run both and require them to agree.

use crate::json;
use crate::value::{
    self, __souther_int, __souther_int_value, __souther_list, __souther_list_get,
    __souther_list_length, __souther_list_set, __souther_string, __souther_string_bytes,
    __souther_string_length,
};
use crate::{abort, alloc, next_free, REASON_OUT_OF_RANGE};

/// `String.length`: how many code points, which is what a character is here.
#[no_mangle]
pub unsafe extern "C" fn __souther_string_size(text: u32) -> u32 {
    __souther_int(code_points(text) as i64)
}

/// `String.slice(fromInclusive, toExclusive, s)`, both by code point so a character is never cut.
#[no_mangle]
pub unsafe extern "C" fn __souther_string_slice(from: u32, to: u32, text: u32) -> u32 {
    let held = code_points(text);
    let first = __souther_int_value(from);
    let last = __souther_int_value(to);
    let start = offset_of(text, first, held);
    let end = offset_of(text, last, held);
    if end < start {
        abort(REASON_OUT_OF_RANGE, 0, last as u64, first as u64);
    }
    __souther_string(__souther_string_bytes(text) + start, end - start)
}

/// `String.append(a, b)`.
#[no_mangle]
pub unsafe extern "C" fn __souther_string_append(left: u32, right: u32) -> u32 {
    value::__souther_concat(left, right)
}

/// `String.reverse`: the characters the other way round, a character at a time.
#[no_mangle]
pub unsafe extern "C" fn __souther_string_reverse(text: u32) -> u32 {
    let bytes = __souther_string_bytes(text);
    let length = __souther_string_length(text);
    let out = next_free();
    let mut at = length;
    while at > 0 {
        let start = start_of_character_before(bytes, at);
        let width = at - start;
        let piece = alloc(width);
        core::ptr::copy_nonoverlapping(
            (bytes + start) as *const u8,
            piece as *mut u8,
            width as usize,
        );
        at = start;
    }
    __souther_string(out, length)
}

/// `String.repeat(n, s)`: nothing for a count of zero or less, and an end to the call for one no
/// string could hold.
#[no_mangle]
pub unsafe extern "C" fn __souther_string_repeat(count: u32, text: u32) -> u32 {
    let times = __souther_int_value(count);
    let length = __souther_string_length(text);
    if times <= 0 || length == 0 {
        return __souther_string(0, 0);
    }
    if times > u32::MAX as i64 || (times as u64) * (length as u64) > u32::MAX as u64 {
        abort(REASON_OUT_OF_RANGE, 0, times as u64, length as u64);
    }
    let bytes = __souther_string_bytes(text);
    let out = next_free();
    for _ in 0..times {
        let piece = alloc(length);
        core::ptr::copy_nonoverlapping(bytes as *const u8, piece as *mut u8, length as usize);
    }
    __souther_string(out, length * times as u32)
}

/// `String.contains(sub, s)`.
#[no_mangle]
pub unsafe extern "C" fn __souther_string_contains(part: u32, text: u32) -> u32 {
    value::__souther_bool(u32::from(index_of(text, part).is_some()))
}

/// `String.startsWith(prefix, s)`.
#[no_mangle]
pub unsafe extern "C" fn __souther_string_starts_with(prefix: u32, text: u32) -> u32 {
    let held = __souther_string_length(prefix);
    value::__souther_bool(u32::from(
        held <= __souther_string_length(text)
            && same(__souther_string_bytes(text), __souther_string_bytes(prefix), held),
    ))
}

/// `String.endsWith(suffix, s)`.
#[no_mangle]
pub unsafe extern "C" fn __souther_string_ends_with(suffix: u32, text: u32) -> u32 {
    let held = __souther_string_length(suffix);
    let length = __souther_string_length(text);
    value::__souther_bool(u32::from(
        held <= length
            && same(
                __souther_string_bytes(text) + (length - held),
                __souther_string_bytes(suffix),
                held,
            ),
    ))
}

/// `String.trim`: what a JVM string's own trim takes off, which is everything at or under a space.
#[no_mangle]
pub unsafe extern "C" fn __souther_string_trim(text: u32) -> u32 {
    let bytes = __souther_string_bytes(text);
    let mut start = 0;
    let mut end = __souther_string_length(text);
    while start < end && core::ptr::read((bytes + start) as *const u8) <= b' ' {
        start += 1;
    }
    while end > start && core::ptr::read((bytes + end - 1) as *const u8) <= b' ' {
        end -= 1;
    }
    __souther_string(bytes + start, end - start)
}

/// `String.fromInt`.
#[no_mangle]
pub unsafe extern "C" fn __souther_string_from_int(number: u32) -> u32 {
    let written = json::__souther_json_write_int(__souther_int_value(number));
    __souther_string(written as u32, (written >> 32) as u32)
}

/// `String.split(sep, s)`, keeping every empty piece. An empty separator gives the whole string.
#[no_mangle]
pub unsafe extern "C" fn __souther_string_split(separator: u32, text: u32, descriptor: u32) -> u32 {
    let bytes = __souther_string_bytes(text);
    let length = __souther_string_length(text);
    let width = __souther_string_length(separator);
    if width == 0 {
        let out = __souther_list(descriptor, 1);
        __souther_list_set(out, 0, __souther_string(bytes, length));
        return out;
    }
    let mut pieces = 1;
    let mut at = 0;
    while at + width <= length {
        if same(bytes + at, __souther_string_bytes(separator), width) {
            pieces += 1;
            at += width;
        } else {
            at += 1;
        }
    }
    let out = __souther_list(descriptor, pieces);
    let mut piece = 0;
    let mut start = 0;
    at = 0;
    while at + width <= length {
        if same(bytes + at, __souther_string_bytes(separator), width) {
            __souther_list_set(out, piece, __souther_string(bytes + start, at - start));
            piece += 1;
            at += width;
            start = at;
        } else {
            at += 1;
        }
    }
    __souther_list_set(out, piece, __souther_string(bytes + start, length - start));
    out
}

/// `String.join(sep, xs)`.
#[no_mangle]
pub unsafe extern "C" fn __souther_string_join(separator: u32, texts: u32) -> u32 {
    let held = __souther_list_length(texts);
    let out = next_free();
    let mut total = 0;
    for i in 0..held {
        if i > 0 {
            total += copied(separator);
        }
        total += copied(__souther_list_get(texts, i));
    }
    __souther_string(out, total)
}

/// `String.concat(xs)`.
#[no_mangle]
pub unsafe extern "C" fn __souther_string_concat_all(texts: u32) -> u32 {
    let out = next_free();
    let mut total = 0;
    for i in 0..__souther_list_length(texts) {
        total += copied(__souther_list_get(texts, i));
    }
    __souther_string(out, total)
}

/// `String.replace(target, replacement, s)`. An empty target leaves the string alone.
#[no_mangle]
pub unsafe extern "C" fn __souther_string_replace(target: u32, with: u32, text: u32) -> u32 {
    let width = __souther_string_length(target);
    if width == 0 {
        return __souther_string(__souther_string_bytes(text), __souther_string_length(text));
    }
    let bytes = __souther_string_bytes(text);
    let length = __souther_string_length(text);
    let out = next_free();
    let mut total = 0;
    let mut at = 0;
    while at < length {
        if at + width <= length && same(bytes + at, __souther_string_bytes(target), width) {
            total += copied(with);
            at += width;
        } else {
            let piece = alloc(1);
            core::ptr::write(piece as *mut u8, core::ptr::read((bytes + at) as *const u8));
            total += 1;
            at += 1;
        }
    }
    __souther_string(out, total)
}

/// `String.characters(s)`: one string per code point.
#[no_mangle]
pub unsafe extern "C" fn __souther_string_characters(text: u32, descriptor: u32) -> u32 {
    let bytes = __souther_string_bytes(text);
    let length = __souther_string_length(text);
    let out = __souther_list(descriptor, code_points(text));
    let mut at = 0;
    let mut i = 0;
    while at < length {
        let width = character_width(core::ptr::read((bytes + at) as *const u8));
        __souther_list_set(out, i, __souther_string(bytes + at, width));
        at += width;
        i += 1;
    }
    out
}

/// `String.codePoints(s)`.
#[no_mangle]
pub unsafe extern "C" fn __souther_string_code_points(text: u32, descriptor: u32) -> u32 {
    let bytes = __souther_string_bytes(text);
    let length = __souther_string_length(text);
    let out = __souther_list(descriptor, code_points(text));
    let mut at = 0;
    let mut i = 0;
    while at < length {
        let first = core::ptr::read((bytes + at) as *const u8) as u32;
        let width = character_width(first as u8);
        let point = match width {
            1 => first,
            2 => ((first & 0x1f) << 6) | trailing(bytes + at, 1),
            3 => ((first & 0x0f) << 12) | (trailing(bytes + at, 1) << 6) | trailing(bytes + at, 2),
            _ => {
                ((first & 0x07) << 18)
                    | (trailing(bytes + at, 1) << 12)
                    | (trailing(bytes + at, 2) << 6)
                    | trailing(bytes + at, 3)
            }
        };
        __souther_list_set(out, i, __souther_int(point as i64));
        at += width;
        i += 1;
    }
    out
}

/// `Int.add`, which is the `+` operator's own account of what leaving the range is.
#[no_mangle]
pub unsafe extern "C" fn __souther_int_add(left: u32, right: u32) -> u32 {
    value::__souther_add(left, right)
}

/// `Int.subtract`.
#[no_mangle]
pub unsafe extern "C" fn __souther_int_subtract(left: u32, right: u32) -> u32 {
    value::__souther_subtract(left, right)
}

/// `Int.multiply`.
#[no_mangle]
pub unsafe extern "C" fn __souther_int_multiply(left: u32, right: u32) -> u32 {
    value::__souther_multiply(left, right)
}

/// `Int.compare`: minus one, zero or one.
#[no_mangle]
pub unsafe extern "C" fn __souther_int_compare(left: u32, right: u32) -> u32 {
    let (a, b) = (__souther_int_value(left), __souther_int_value(right));
    __souther_int(if a < b {
        -1
    } else if a > b {
        1
    } else {
        0
    })
}

/// `Int.floorMod`: the remainder of a floored division, so its sign is the divisor's.
#[no_mangle]
pub unsafe extern "C" fn __souther_int_floor_mod(dividend: u32, divisor: u32) -> u32 {
    let (a, b) = (__souther_int_value(dividend), __souther_int_value(divisor));
    if b == 0 {
        abort(crate::REASON_DIVISION_BY_ZERO, 0, a as u64, 0);
    }
    match a.checked_rem(b) {
        Some(rest) => {
            let held = if rest != 0 && (rest < 0) != (b < 0) { rest + b } else { rest };
            __souther_int(held)
        }
        None => __souther_int(0),
    }
}

/// `List.length`.
#[no_mangle]
pub unsafe extern "C" fn __souther_list_size(list: u32) -> u32 {
    __souther_int(__souther_list_length(list) as i64)
}

/// `List.reverse`.
#[no_mangle]
pub unsafe extern "C" fn __souther_list_reverse(list: u32, descriptor: u32) -> u32 {
    let held = __souther_list_length(list);
    let out = __souther_list(descriptor, held);
    for i in 0..held {
        __souther_list_set(out, held - 1 - i, __souther_list_get(list, i));
    }
    out
}

/// `List.sum` over whole numbers.
#[no_mangle]
pub unsafe extern "C" fn __souther_list_sum(list: u32) -> u32 {
    let mut total = __souther_int(0);
    for i in 0..__souther_list_length(list) {
        total = value::__souther_add(total, __souther_list_get(list, i));
    }
    total
}

/// `List.product` over whole numbers.
#[no_mangle]
pub unsafe extern "C" fn __souther_list_product(list: u32) -> u32 {
    let mut total = __souther_int(1);
    for i in 0..__souther_list_length(list) {
        total = value::__souther_multiply(total, __souther_list_get(list, i));
    }
    total
}

/// `List.rangeInclusive(from, to)`, empty where the end is before the start.
#[no_mangle]
pub unsafe extern "C" fn __souther_list_range(first: u32, last: u32, descriptor: u32) -> u32 {
    let from = __souther_int_value(first);
    let to = __souther_int_value(last);
    if to < from {
        return __souther_list(descriptor, 0);
    }
    let span = (to as i128) - (from as i128) + 1;
    if span > u32::MAX as i128 {
        abort(REASON_OUT_OF_RANGE, 0, from as u64, to as u64);
    }
    let out = __souther_list(descriptor, span as u32);
    for i in 0..span as u32 {
        __souther_list_set(out, i, __souther_int(from + i as i64));
    }
    out
}

/// Copies a string's bytes onto the arena's top and answers how many.
unsafe fn copied(text: u32) -> u32 {
    let length = __souther_string_length(text);
    let at = alloc(length);
    core::ptr::copy_nonoverlapping(
        __souther_string_bytes(text) as *const u8,
        at as *mut u8,
        length as usize,
    );
    length
}

/// How many code points a string holds.
unsafe fn code_points(text: u32) -> u32 {
    let bytes = __souther_string_bytes(text);
    let length = __souther_string_length(text);
    let mut held = 0;
    let mut at = 0;
    while at < length {
        at += character_width(core::ptr::read((bytes + at) as *const u8));
        held += 1;
    }
    held
}

/// The byte a code point index stands at. Out of range ends the call, wherever it was written.
unsafe fn offset_of(text: u32, index: i64, held: u32) -> u32 {
    if index < 0 || index > held as i64 {
        abort(REASON_OUT_OF_RANGE, 0, index as u64, held as u64);
    }
    let bytes = __souther_string_bytes(text);
    let mut at = 0;
    for _ in 0..index {
        at += character_width(core::ptr::read((bytes + at) as *const u8));
    }
    at
}

/// Where the character ending at a byte starts.
unsafe fn start_of_character_before(bytes: u32, at: u32) -> u32 {
    let mut start = at - 1;
    while start > 0 && (core::ptr::read((bytes + start) as *const u8) & 0xc0) == 0x80 {
        start -= 1;
    }
    start
}

fn character_width(first: u8) -> u32 {
    if first < 0x80 {
        1
    } else if first < 0xe0 {
        2
    } else if first < 0xf0 {
        3
    } else {
        4
    }
}

unsafe fn trailing(at: u32, offset: u32) -> u32 {
    (core::ptr::read((at + offset) as *const u8) as u32) & 0x3f
}

unsafe fn index_of(text: u32, part: u32) -> Option<u32> {
    let width = __souther_string_length(part);
    let length = __souther_string_length(text);
    if width > length {
        return None;
    }
    for at in 0..=(length - width) {
        if same(
            __souther_string_bytes(text) + at,
            __souther_string_bytes(part),
            width,
        ) {
            return Some(at);
        }
    }
    None
}

unsafe fn same(left: u32, right: u32, length: u32) -> bool {
    for i in 0..length as usize {
        if core::ptr::read((left as usize + i) as *const u8)
            != core::ptr::read((right as usize + i) as *const u8)
        {
            return false;
        }
    }
    true
}
