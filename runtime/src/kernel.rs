//! The operations the standard library declares as intrinsic.
//!
//! Each takes its arguments in the order the library's own signature writes them, so a call site
//! hands over what the call held and nothing rearranges anything on the way.
//!
//! What each one means belongs to Souther. These are written against `souther.runtime`'s own
//! account of it — a `String` is measured and cut in code points, a whole number that leaves the
//! range ends the call, a count no string could reach ends it rather than quietly making fewer
//! copies than were asked for — and the tests run both and require them to agree.

use crate::descriptor;
use crate::json;
use crate::order;
use crate::regex;
use crate::temporal;
use crate::value::{
    self, __souther_int, __souther_int_value, __souther_list, __souther_list_get,
    __souther_list_length, __souther_list_set, __souther_string, __souther_string_bytes,
    __souther_string_length,
};
use crate::{abort, alloc, next_free, REASON_INVALID_BOUNDS, REASON_REQUIRED_FORM_HAS_NO_PLACE};

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
        abort(REASON_INVALID_BOUNDS, 0, last as u64, first as u64);
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
        abort(REASON_REQUIRED_FORM_HAS_NO_PLACE, 0, times as u64, length as u64);
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

/// `String.matches(pattern, s)`, where the pattern text the checker settled was compiled ahead of
/// the run and reaches here as the machine that recognises it.
#[no_mangle]
pub unsafe extern "C" fn __souther_string_matches(text: u32, machine: u32) -> u32 {
    value::__souther_bool(u32::from(regex::matches(
        __souther_string_bytes(text),
        __souther_string_length(text),
        machine,
    )))
}

/// A moment a body wrote down, read from the text it was written as.
#[no_mangle]
pub unsafe extern "C" fn __souther_instant_written(at: u32, length: u32) -> u32 {
    let (second, nano) = temporal::read_moment(at, length).unwrap_or((0, 0));
    temporal::moment_made(second, nano)
}

/// An amount a body wrote down, read from the text it was written as.
///
/// The text sits in static memory and the value is built where it is used, because a value lives
/// on the arena and the arena is reset between calls. What the text says was settled where it was
/// written, so nothing here can fail to read it.
#[no_mangle]
pub unsafe extern "C" fn __souther_decimal_written(at: u32, length: u32) -> u32 {
    crate::decimal::parse(at, length)
}

/// A day a body wrote down, read from the text it was written as.
///
/// Three names rather than one told which, because the number a tag goes by is the runtime's and
/// writing it down on the other side would be a second account of the same thing.
#[no_mangle]
pub unsafe extern "C" fn __souther_date_written(at: u32, length: u32) -> u32 {
    temporal::made(value::TAG_DATE, temporal::read_day(at, length).unwrap_or(0), 0)
}

/// A time of day a body wrote down, read from the text it was written as.
#[no_mangle]
pub unsafe extern "C" fn __souther_time_written(at: u32, length: u32) -> u32 {
    temporal::made(value::TAG_TIME, 0, temporal::read_time(at, length).unwrap_or(0))
}

/// A day and a time together that a body wrote down, read from the text it was written as.
#[no_mangle]
pub unsafe extern "C" fn __souther_datetime_written(at: u32, length: u32) -> u32 {
    let (day, second) = temporal::read_both(at, length).unwrap_or((0, 0));
    temporal::made(value::TAG_DATE_TIME, day, second)
}

/// `String.fromDecimal(d)`.
#[no_mangle]
pub unsafe extern "C" fn __souther_string_from_decimal(amount: u32) -> u32 {
    let (at, length) = crate::decimal::written_in_full(amount);
    __souther_string(at, length)
}

/// `String.toDecimal(s)`, which answers the case it is told the name of where the text is no
/// amount — the same way `String.toInt` answers one.
#[no_mangle]
pub unsafe extern "C" fn __souther_string_to_decimal(text: u32, absent: u32) -> u32 {
    let held = crate::decimal::parse(__souther_string_bytes(text), __souther_string_length(text));
    if held == 0 {
        return value::__souther_unit(absent);
    }
    held
}

/// `Option.map(f, opt)`: what the block answers for what the option holds, or nothing.
#[no_mangle]
pub unsafe extern "C" fn __souther_option_map(block: u32, held: u32) -> u32 {
    if value::__souther_is_some(held) == 0 {
        return value::__souther_none();
    }
    value::__souther_some(crate::__souther_call_block(block, value::__souther_held(held)))
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

/// `String.trim`: removes a maximal run of String whitespace (spec §string-whitespace) from each
/// end, leaving the rest untouched. Scans code points via the existing UTF-8 primitives, so a
/// character outside the whitespace set stops the run rather than being crossed as a byte would be.
#[no_mangle]
pub unsafe extern "C" fn __souther_string_trim(text: u32) -> u32 {
    let bytes = __souther_string_bytes(text);
    let mut start = 0;
    let mut end = __souther_string_length(text);
    while start < end {
        let width = character_width(core::ptr::read((bytes + start) as *const u8));
        let point = code_point_at(bytes + start, width);
        if !string_whitespace(point) {
            break;
        }
        start += width;
    }
    while end > start {
        let before = start_of_character_before(bytes, end);
        let point = code_point_at(bytes + before, end - before);
        if !string_whitespace(point) {
            break;
        }
        end = before;
    }
    __souther_string(bytes + start, end - start)
}

/// `String.lowercase(s)`: Unicode 18.0.0's default case conversion, untailored (issue #21). See
/// `crate::casing` for the algorithm — this is an ABI wrapper only.
#[no_mangle]
pub unsafe extern "C" fn __souther_string_lowercase(text: u32) -> u32 {
    crate::casing::lowercase(text)
}

/// `String.uppercase(s)`. See `crate::casing`.
#[no_mangle]
pub unsafe extern "C" fn __souther_string_uppercase(text: u32) -> u32 {
    crate::casing::uppercase(text)
}

/// `String.words(s)`: the pieces between runs of String whitespace (spec §string-whitespace),
/// with none empty. Two passes over `next_word` — one to size the list, one to fill it — since the
/// list must be allocated to its final length before anything is written into it.
#[no_mangle]
pub unsafe extern "C" fn __souther_string_words(text: u32, descriptor: u32) -> u32 {
    let bytes = __souther_string_bytes(text);
    let length = __souther_string_length(text);
    let mut held = 0;
    let mut at = 0;
    while let Some((_, end)) = next_word(bytes, length, at) {
        held += 1;
        at = end;
    }
    let out = __souther_list(descriptor, held);
    let mut i = 0;
    at = 0;
    while let Some((start, end)) = next_word(bytes, length, at) {
        __souther_list_set(out, i, __souther_string(bytes + start, end - start));
        i += 1;
        at = end;
    }
    out
}

/// The next word in `bytes[0..length]` at or after `at`: the byte range of a maximal run of
/// non-whitespace code points, skipping any run of String whitespace first. `None` once nothing
/// but whitespace remains. The one place word boundaries are decided, so `words`'s two passes
/// cannot drift apart.
unsafe fn next_word(bytes: u32, length: u32, mut at: u32) -> Option<(u32, u32)> {
    while at < length {
        let width = character_width(core::ptr::read((bytes + at) as *const u8));
        let point = code_point_at(bytes + at, width);
        if !string_whitespace(point) {
            break;
        }
        at += width;
    }

    if at == length {
        return None;
    }

    let start = at;

    while at < length {
        let width = character_width(core::ptr::read((bytes + at) as *const u8));
        let point = code_point_at(bytes + at, width);
        if string_whitespace(point) {
            break;
        }
        at += width;
    }

    Some((start, at))
}

/// `String.lines(s)`: what `split` on a newline gives, after a carriage return before one is gone.
#[no_mangle]
pub unsafe extern "C" fn __souther_string_lines(text: u32, descriptor: u32) -> u32 {
    let bytes = __souther_string_bytes(text);
    let length = __souther_string_length(text);
    let out = next_free();
    let mut total = 0;
    let mut at = 0;
    while at < length {
        let byte = core::ptr::read((bytes + at) as *const u8);
        if byte == b'\r' && at + 1 < length && core::ptr::read((bytes + at + 1) as *const u8) == b'\n'
        {
            at += 1;
            continue;
        }
        let piece = alloc(1);
        core::ptr::write(piece as *mut u8, byte);
        total += 1;
        at += 1;
    }
    let joined = __souther_string(out, total);
    let newline = __souther_string(b"\n".as_ptr() as u32, 1);
    __souther_string_split(newline, joined, descriptor)
}

/// `String.padLeft(width, pad, s)` and `String.padRight(width, pad, s)`.
#[no_mangle]
pub unsafe extern "C" fn __souther_string_pad_left(width: u32, pad: u32, text: u32) -> u32 {
    let fill = padding(width, pad, text);
    if __souther_string_length(fill) == 0 {
        return text;
    }
    value::__souther_concat(fill, text)
}

/// `String.padRight(width, pad, s)`.
#[no_mangle]
pub unsafe extern "C" fn __souther_string_pad_right(width: u32, pad: u32, text: u32) -> u32 {
    let fill = padding(width, pad, text);
    if __souther_string_length(fill) == 0 {
        return text;
    }
    value::__souther_concat(text, fill)
}

/// What brings a string up to exactly a width in code points, cut so a long pad does not overshoot.
unsafe fn padding(width: u32, pad: u32, text: u32) -> u32 {
    let wanted = __souther_int_value(width);
    let missing = wanted - code_points(text) as i64;
    if missing <= 0 || __souther_string_length(pad) == 0 {
        return __souther_string(0, 0);
    }
    if missing > u32::MAX as i64 {
        abort(REASON_REQUIRED_FORM_HAS_NO_PLACE, 0, wanted as u64, 0);
    }
    let each = code_points(pad) as i64;
    let times = (missing + each - 1) / each;
    let repeated = __souther_string_repeat(__souther_int(times), pad);
    let cut = __souther_string_slice(__souther_int(0), __souther_int(missing), repeated);
    cut
}

/// String whitespace (spec §string-whitespace): the fixed 25-code-point set `trim` and `words`
/// both scan by. Enumerated rather than read off `char::is_whitespace` or a Unicode table, so a
/// toolchain's Unicode version does not silently change what a Souther program means. Mirrors
/// `souther.runtime.Strings.isWhitespace` in the JVM backend exactly.
fn string_whitespace(point: u32) -> bool {
    matches!(
        point,
        0x0009..=0x000d
            | 0x0020
            | 0x0085
            | 0x00a0
            | 0x1680
            | 0x2000..=0x200a
            | 0x2028
            | 0x2029
            | 0x202f
            | 0x205f
            | 0x3000
    )
}

pub(crate) unsafe fn code_point_at(at: u32, width: u32) -> u32 {
    let first = core::ptr::read(at as *const u8) as u32;
    match width {
        1 => first,
        2 => ((first & 0x1f) << 6) | trailing(at, 1),
        3 => ((first & 0x0f) << 12) | (trailing(at, 1) << 6) | trailing(at, 2),
        _ => {
            ((first & 0x07) << 18)
                | (trailing(at, 1) << 12)
                | (trailing(at, 2) << 6)
                | trailing(at, 3)
        }
    }
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

/// `Int.divide(dividend, divisor)`: the quotient, or the case a zero divisor is.
///
/// A zero divisor is a business case here rather than a model bug — that is what the declaration's
/// type says — so it is answered with, not aborted on. Told which case by the caller, because
/// which one the library names is the declaration's answer and not a value's.
#[no_mangle]
pub unsafe extern "C" fn __souther_int_divide(dividend: u32, divisor: u32, absent: u32) -> u32 {
    let (a, b) = (__souther_int_value(dividend), __souther_int_value(divisor));
    if b == 0 {
        return value::__souther_unit(absent);
    }
    match a.checked_div(b) {
        Some(quotient) => __souther_int(quotient),
        None => abort(crate::REASON_REQUIRED_FORM_HAS_NO_PLACE, 0, a as u64, b as u64),
    }
}

/// `Int.truncatingRemainder(dividend, divisor)`: the remainder of a truncating division, so its
/// sign is the dividend's, or the case a zero divisor is.
#[no_mangle]
pub unsafe extern "C" fn __souther_int_remainder(dividend: u32, divisor: u32, absent: u32) -> u32 {
    let (a, b) = (__souther_int_value(dividend), __souther_int_value(divisor));
    if b == 0 {
        return value::__souther_unit(absent);
    }
    match a.checked_rem(b) {
        Some(rest) => __souther_int(rest),
        None => abort(crate::REASON_REQUIRED_FORM_HAS_NO_PLACE, 0, a as u64, b as u64),
    }
}

/// `String.toInt(s)`: the whole number the text is, or the case it is not one.
#[no_mangle]
pub unsafe extern "C" fn __souther_string_to_int(text: u32, absent: u32) -> u32 {
    let bytes = __souther_string_bytes(text);
    let length = __souther_string_length(text);
    if length == 0 {
        return value::__souther_unit(absent);
    }
    let negative = core::ptr::read(bytes as *const u8) == b'-';
    let positive = core::ptr::read(bytes as *const u8) == b'+';
    let mut at = u32::from(negative || positive);
    if at == length {
        return value::__souther_unit(absent);
    }
    let mut magnitude: u128 = 0;
    while at < length {
        let digit = core::ptr::read((bytes + at) as *const u8);
        if !digit.is_ascii_digit() {
            return value::__souther_unit(absent);
        }
        magnitude = magnitude * 10 + (digit - b'0') as u128;
        if magnitude > 1u128 << 63 {
            return value::__souther_unit(absent);
        }
        at += 1;
    }
    let limit = if negative { 1u128 << 63 } else { i64::MAX as u128 };
    if magnitude > limit {
        return value::__souther_unit(absent);
    }
    if negative {
        __souther_int((magnitude as i128).wrapping_neg() as i64)
    } else {
        __souther_int(magnitude as i64)
    }
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

/// `List.get(index, xs)`: what the list holds there, or nothing where it holds nothing there.
#[no_mangle]
pub unsafe extern "C" fn __souther_list_at(index: u32, list: u32) -> u32 {
    let at = __souther_int_value(index);
    if at < 0 || at >= __souther_list_length(list) as i64 {
        return value::__souther_none();
    }
    value::__souther_some(__souther_list_get(list, at as u32))
}

/// `List.find(p, xs)`: the first element the block holds for, or nothing.
///
/// Written here rather than as a walk in the generated body, because the block is a value by then
/// and calling one is the same wherever it is done.
#[no_mangle]
pub unsafe extern "C" fn __souther_list_find(kept: u32, list: u32) -> u32 {
    for i in 0..__souther_list_length(list) {
        let each = __souther_list_get(list, i);
        if value::__souther_bool_value(crate::__souther_call_block(kept, each)) != 0 {
            return value::__souther_some(each);
        }
    }
    value::__souther_none()
}

/// `List.sort(xs)`: the elements in the order their type places them.
#[no_mangle]
pub unsafe extern "C" fn __souther_list_sort(list: u32, descriptor: u32) -> u32 {
    let element = descriptor::member(descriptor, 0);
    let held = __souther_list_length(list);
    let out = __souther_list(descriptor, held);
    for i in 0..held {
        __souther_list_set(out, i, __souther_list_get(list, i));
    }
    merge_sorted(out, out, element);
    out
}

/// Sorts a list in place, in the order a second list's elements place them.
///
/// Merged in runs that double, and the two lists move together so that sorting by what a block
/// answered moves what it was answered about. Two elements a comparison cannot separate keep the
/// order they were written in, which is what a caller sorting twice by two things relies on.
///
/// The scratch is the arena's, which is where everything a call makes lives. An insertion sort
/// wants none, and that is the whole of what it has to recommend it: a list twice as long costs
/// four times as much to sort, and a list is as long as whoever sent it wanted.
unsafe fn merge_sorted(list: u32, by: u32, element: u32) {
    let held = __souther_list_length(list);
    if held < 2 {
        return;
    }
    let room = alloc(8 * held);
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
                    order::ranked(__souther_list_get(by, left), __souther_list_get(by, right),
                            element) <= 0
                };
                let taken = if take_left {
                    left += 1;
                    left - 1
                } else {
                    right += 1;
                    right - 1
                };
                core::ptr::write_unaligned(
                    (room + into * 8) as *mut u32, __souther_list_get(list, taken));
                core::ptr::write_unaligned(
                    (room + into * 8 + 4) as *mut u32, __souther_list_get(by, taken));
                into += 1;
            }
            at += 2 * width;
        }
        for i in 0..held {
            __souther_list_set(list, i, core::ptr::read_unaligned((room + i * 8) as *const u32));
            __souther_list_set(by, i, core::ptr::read_unaligned((room + i * 8 + 4) as *const u32));
        }
        width *= 2;
    }
}

/// The furthest one either way, or nothing where there is none: the greatest when `maximum`.
unsafe fn list_furthest(list: u32, maximum: bool) -> u32 {
    let held = __souther_list_length(list);
    if held == 0 {
        return value::__souther_none();
    }
    let descriptor = core::ptr::read_unaligned((list as usize + 4) as *const u32);
    let element = descriptor::member(descriptor, 0);
    let mut best = __souther_list_get(list, 0);
    for i in 1..held {
        let each = __souther_list_get(list, i);
        let against = order::ranked(each, best, element);
        if (maximum && against > 0) || (!maximum && against < 0) {
            best = each;
        }
    }
    value::__souther_some(best)
}

/// `List.max(xs)`.
#[no_mangle]
pub unsafe extern "C" fn __souther_list_max(list: u32) -> u32 {
    list_furthest(list, true)
}

/// `List.min(xs)`.
#[no_mangle]
pub unsafe extern "C" fn __souther_list_min(list: u32) -> u32 {
    list_furthest(list, false)
}

/// `List.sortBy(key, xs)`: the elements in the order what the block answers of each places them.
#[no_mangle]
pub unsafe extern "C" fn __souther_list_sort_by(
    key: u32,
    list: u32,
    descriptor: u32,
    keys: u32,
) -> u32 {
    let held = __souther_list_length(list);
    let out = __souther_list(descriptor, held);
    let by = __souther_list(descriptor, held);
    for i in 0..held {
        let each = __souther_list_get(list, i);
        __souther_list_set(out, i, each);
        __souther_list_set(by, i, crate::__souther_call_block(key, each));
    }
    merge_sorted(out, by, keys);
    out
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
pub unsafe extern "C" fn __souther_list_sum(list: u32, descriptor: u32) -> u32 {
    // What a total of nothing is, and what every step of it is worked out in, are the same
    // question: the list's own element. A whole-number zero added to an amount reads the amount's
    // bytes as a whole number's, and answers.
    if descriptor::kind(descriptor) == descriptor::KIND_DECIMAL {
        let mut total = crate::decimal::of_digits(crate::next_free(), 0, 0, false);
        for i in 0..__souther_list_length(list) {
            total = crate::decimal::__souther_decimal_add(total, __souther_list_get(list, i));
        }
        return total;
    }
    let mut total = __souther_int(0);
    for i in 0..__souther_list_length(list) {
        total = value::__souther_add(total, __souther_list_get(list, i));
    }
    total
}

/// `List.product` over whole numbers.
#[no_mangle]
pub unsafe extern "C" fn __souther_list_product(list: u32, descriptor: u32) -> u32 {
    if descriptor::kind(descriptor) == descriptor::KIND_DECIMAL {
        let one = crate::next_free();
        let _ = crate::alloc(1);
        core::ptr::write(one as *mut u8, b'1');
        let mut total = crate::decimal::of_digits(one, 1, 0, false);
        for i in 0..__souther_list_length(list) {
            total = crate::decimal::__souther_decimal_multiply(total, __souther_list_get(list, i));
        }
        return total;
    }
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
        abort(REASON_REQUIRED_FORM_HAS_NO_PLACE, 0, from as u64, to as u64);
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
        abort(REASON_INVALID_BOUNDS, 0, index as u64, held as u64);
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

pub(crate) fn character_width(first: u8) -> u32 {
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

/// `Set.empty`, `Set.singleton(value)` and the rest of what a set is asked for.
///
/// A set is the array of its members in the order they are written, each held once, so every one
/// of these keeps that: what comes out is sorted and has no member twice, whatever went in.
#[no_mangle]
pub unsafe extern "C" fn __souther_set_empty(descriptor: u32) -> u32 {
    __souther_list(descriptor, 0)
}

/// `Set.singleton(value)`.
#[no_mangle]
pub unsafe extern "C" fn __souther_set_singleton(value: u32, descriptor: u32) -> u32 {
    let out = __souther_list(descriptor, 1);
    __souther_list_set(out, 0, value);
    out
}

/// `Set.insert(value, s)`.
#[no_mangle]
pub unsafe extern "C" fn __souther_set_insert(value: u32, set: u32, descriptor: u32) -> u32 {
    let held = __souther_list_length(set);
    let element = descriptor::member(descriptor, 0);
    for i in 0..held {
        if order::compare(__souther_list_get(set, i), value, element) == 0 {
            return set;
        }
    }
    let out = __souther_list(descriptor, held + 1);
    let mut at = 0;
    let mut placed = false;
    for i in 0..held {
        let each = __souther_list_get(set, i);
        if !placed && order::compare(value, each, element) < 0 {
            __souther_list_set(out, at, value);
            at += 1;
            placed = true;
        }
        __souther_list_set(out, at, each);
        at += 1;
    }
    if !placed {
        __souther_list_set(out, at, value);
    }
    out
}

/// `Set.remove(value, s)`.
#[no_mangle]
pub unsafe extern "C" fn __souther_set_remove(value: u32, set: u32, descriptor: u32) -> u32 {
    let held = __souther_list_length(set);
    let element = descriptor::member(descriptor, 0);
    let mut keeping = 0;
    for i in 0..held {
        if order::compare(__souther_list_get(set, i), value, element) != 0 {
            keeping += 1;
        }
    }
    let out = __souther_list(descriptor, keeping);
    let mut at = 0;
    for i in 0..held {
        let each = __souther_list_get(set, i);
        if order::compare(each, value, element) != 0 {
            __souther_list_set(out, at, each);
            at += 1;
        }
    }
    out
}

/// `Set.contains(value, s)`.
#[no_mangle]
pub unsafe extern "C" fn __souther_set_contains(value: u32, set: u32) -> u32 {
    let descriptor = core::ptr::read_unaligned((set as usize + 4) as *const u32);
    let element = descriptor::member(descriptor, 0);
    for i in 0..__souther_list_length(set) {
        if order::compare(__souther_list_get(set, i), value, element) == 0 {
            return value::__souther_bool(1);
        }
    }
    value::__souther_bool(0)
}

/// `Set.union(a, b)`.
#[no_mangle]
pub unsafe extern "C" fn __souther_set_union(left: u32, right: u32, descriptor: u32) -> u32 {
    let mut out = left;
    for i in 0..__souther_list_length(right) {
        out = __souther_set_insert(__souther_list_get(right, i), out, descriptor);
    }
    out
}

/// `Set.intersection(a, b)`.
#[no_mangle]
pub unsafe extern "C" fn __souther_set_intersection(left: u32, right: u32, descriptor: u32) -> u32 {
    let mut out = __souther_set_empty(descriptor);
    for i in 0..__souther_list_length(left) {
        let each = __souther_list_get(left, i);
        if value::__souther_bool_value(__souther_set_contains(each, right)) != 0 {
            out = __souther_set_insert(each, out, descriptor);
        }
    }
    out
}

/// `Set.difference(a, b)`.
#[no_mangle]
pub unsafe extern "C" fn __souther_set_difference(left: u32, right: u32, descriptor: u32) -> u32 {
    let mut out = __souther_set_empty(descriptor);
    for i in 0..__souther_list_length(left) {
        let each = __souther_list_get(left, i);
        if value::__souther_bool_value(__souther_set_contains(each, right)) == 0 {
            out = __souther_set_insert(each, out, descriptor);
        }
    }
    out
}

/// `Set.isEmpty(s)` and `Map.isEmpty(m)`.
#[no_mangle]
pub unsafe extern "C" fn __souther_is_empty(collection: u32) -> u32 {
    value::__souther_bool(u32::from(sized(collection) == 0))
}

/// `Set.size(s)` and `Map.size(m)`.
#[no_mangle]
pub unsafe extern "C" fn __souther_size_of(collection: u32) -> u32 {
    __souther_int(sized(collection) as i64)
}

/// `Set.toList(s)`: the members in the order the set holds them.
#[no_mangle]
pub unsafe extern "C" fn __souther_set_to_list(set: u32, descriptor: u32) -> u32 {
    let held = __souther_list_length(set);
    let out = __souther_list(descriptor, held);
    for i in 0..held {
        __souther_list_set(out, i, __souther_list_get(set, i));
    }
    out
}

/// `Set.fromList(xs)`.
#[no_mangle]
pub unsafe extern "C" fn __souther_set_from_list(list: u32, descriptor: u32) -> u32 {
    let mut out = __souther_set_empty(descriptor);
    for i in 0..__souther_list_length(list) {
        out = __souther_set_insert(__souther_list_get(list, i), out, descriptor);
    }
    out
}

/// `Map.empty`.
#[no_mangle]
pub unsafe extern "C" fn __souther_map_empty(descriptor: u32) -> u32 {
    value::__souther_map(descriptor, 0)
}

/// `Map.get(key, m)`.
#[no_mangle]
pub unsafe extern "C" fn __souther_map_get(key: u32, map: u32) -> u32 {
    match entry_of(key, map) {
        Some(at) => value::__souther_some(value::__souther_map_value(map, at)),
        None => value::__souther_none(),
    }
}

/// `Map.containsKey(key, m)`.
#[no_mangle]
pub unsafe extern "C" fn __souther_map_contains(key: u32, map: u32) -> u32 {
    value::__souther_bool(u32::from(entry_of(key, map).is_some()))
}

/// `Map.keys(m)`.
#[no_mangle]
pub unsafe extern "C" fn __souther_map_keys(map: u32, descriptor: u32) -> u32 {
    let held = value::__souther_map_length(map);
    let out = __souther_list(descriptor, held);
    for i in 0..held {
        __souther_list_set(out, i, value::__souther_map_key(map, i));
    }
    out
}

/// `Map.values(m)`, in the order of the keys they stand under.
#[no_mangle]
pub unsafe extern "C" fn __souther_map_values(map: u32, descriptor: u32) -> u32 {
    let held = value::__souther_map_length(map);
    let out = __souther_list(descriptor, held);
    for i in 0..held {
        __souther_list_set(out, i, value::__souther_map_value(map, i));
    }
    out
}

/// `Map.singleton(key, value)`.
#[no_mangle]
pub unsafe extern "C" fn __souther_map_singleton(key: u32, held: u32, descriptor: u32) -> u32 {
    let out = value::__souther_map(descriptor, 1);
    value::__souther_map_set(out, 0, key, held);
    out
}

/// `Map.insert(key, value, m)`: the map with that key standing over that value.
#[no_mangle]
pub unsafe extern "C" fn __souther_map_insert(
    key: u32,
    held: u32,
    map: u32,
    descriptor: u32,
) -> u32 {
    let entries = value::__souther_map_length(map);
    if let Some(at) = entry_of(key, map) {
        let out = value::__souther_map(descriptor, entries);
        for i in 0..entries {
            let value_of = if i == at { held } else { value::__souther_map_value(map, i) };
            value::__souther_map_set(out, i, value::__souther_map_key(map, i), value_of);
        }
        return out;
    }
    let out = value::__souther_map(descriptor, entries + 1);
    let keys = value::map_keys(map);
    let mut at = 0;
    let mut placed = false;
    for i in 0..entries {
        let each = value::__souther_map_key(map, i);
        if !placed && written_before(key, each, keys) {
            value::__souther_map_set(out, at, key, held);
            at += 1;
            placed = true;
        }
        value::__souther_map_set(out, at, each, value::__souther_map_value(map, i));
        at += 1;
    }
    if !placed {
        value::__souther_map_set(out, at, key, held);
    }
    out
}

/// `Map.toList(m)`: a pair per entry, in the order the map holds them.
#[no_mangle]
pub unsafe extern "C" fn __souther_map_to_list(map: u32, descriptor: u32) -> u32 {
    let held = value::__souther_map_length(map);
    let out = __souther_list(descriptor, held);
    for i in 0..held {
        let pair = value::__souther_tuple(2);
        value::__souther_tuple_set(pair, 0, value::__souther_map_key(map, i));
        value::__souther_tuple_set(pair, 1, value::__souther_map_value(map, i));
        __souther_list_set(out, i, pair);
    }
    out
}

/// `Map.fromList(entries)`: what the pairs say, the last of two at one key standing.
#[no_mangle]
pub unsafe extern "C" fn __souther_map_from_list(list: u32, descriptor: u32) -> u32 {
    let mut out = __souther_map_empty(descriptor);
    for i in 0..__souther_list_length(list) {
        let pair = __souther_list_get(list, i);
        out = __souther_map_insert(
            value::__souther_tuple_get(pair, 0),
            value::__souther_tuple_get(pair, 1),
            out,
            descriptor,
        );
    }
    out
}

/// `Map.remove(key, m)`.
#[no_mangle]
pub unsafe extern "C" fn __souther_map_remove(key: u32, map: u32, descriptor: u32) -> u32 {
    let entries = value::__souther_map_length(map);
    let gone = entry_of(key, map);
    if gone.is_none() {
        return map;
    }
    let out = value::__souther_map(descriptor, entries - 1);
    let mut at = 0;
    for i in 0..entries {
        if Some(i) == gone {
            continue;
        }
        value::__souther_map_set(
            out,
            at,
            value::__souther_map_key(map, i),
            value::__souther_map_value(map, i),
        );
        at += 1;
    }
    out
}

/// Where a key stands in a map, or nowhere.
///
/// By what the key is written as, because that is what one entry of a map is: a member of an
/// object, and two spellings of one moment name one member.
///
/// Halved rather than walked: a map's entries stand in the order their keys are written, so
/// whether a key is there is answered by asking the middle one and dropping the half it is not in.
unsafe fn entry_of(key: u32, map: u32) -> Option<u32> {
    let keys = value::map_keys(map);
    let (wanted, wanted_length) = value::key_text(key, keys);
    let mut low = 0;
    let mut high = value::__souther_map_length(map);
    while low < high {
        let middle = low + (high - low) / 2;
        let (each, each_length) = value::key_text(value::__souther_map_key(map, middle), keys);
        let held = order::compare_runs(each, each_length, wanted, wanted_length);
        if held == 0 {
            return Some(middle);
        }
        if held < 0 {
            low = middle + 1;
        } else {
            high = middle;
        }
    }
    None
}

/// Whether one key is written before another, which is the order a map's entries stand in.
unsafe fn written_before(key: u32, other: u32, keys: u32) -> bool {
    let (a, a_length) = value::key_text(key, keys);
    let (b, b_length) = value::key_text(other, keys);
    order::compare_runs(a, a_length, b, b_length) < 0
}

/// How many a set or a map holds.
unsafe fn sized(collection: u32) -> u32 {
    if core::ptr::read_unaligned(collection as usize as *const u32) == value::TAG_MAP {
        value::__souther_map_length(collection)
    } else {
        __souther_list_length(collection)
    }
}

/// `Date.addDays(days, d)`, and the same for a `DateTime`.
#[no_mangle]
pub unsafe extern "C" fn __souther_date_add_days(by: u32, cell: u32) -> u32 {
    let held = temporal::moved(temporal::day(cell), __souther_int_value(by));
    temporal::made(temporal::tag_of(cell), held, temporal::second(cell))
}

/// `Date.addMonths(months, d)`: the same day of a later month, kept inside the month it lands in.
#[no_mangle]
pub unsafe extern "C" fn __souther_date_add_months(by: u32, cell: u32) -> u32 {
    let held = temporal::moved_by_months(temporal::day(cell), __souther_int_value(by));
    temporal::made(temporal::tag_of(cell), held, temporal::second(cell))
}

/// `Date.addYears(years, d)`.
#[no_mangle]
pub unsafe extern "C" fn __souther_date_add_years(by: u32, cell: u32) -> u32 {
    let years = __souther_int_value(by);
    let held = temporal::moved_by_months(temporal::day(cell), years * 12);
    temporal::made(temporal::tag_of(cell), held, temporal::second(cell))
}

/// `Date.daysBetween(from, to)`.
#[no_mangle]
pub unsafe extern "C" fn __souther_date_days_between(from: u32, to: u32) -> u32 {
    __souther_int(temporal::day(to) as i64 - temporal::day(from) as i64)
}

enum DatePart {
    Year,
    Month,
    Day,
}

/// One walk over what a day is, which `Date.year`, `Date.month` and `Date.day` differ from in the last step.
unsafe fn date_part(cell: u32, part: DatePart) -> u32 {
    let (year, month, day) = temporal::civil(temporal::day(cell));
    __souther_int(match part {
        DatePart::Year => year,
        DatePart::Month => month as i64,
        DatePart::Day => day as i64,
    })
}

/// `Date.year(d)`.
#[no_mangle]
pub unsafe extern "C" fn __souther_date_year(cell: u32) -> u32 {
    date_part(cell, DatePart::Year)
}

/// `Date.month(d)`.
#[no_mangle]
pub unsafe extern "C" fn __souther_date_month(cell: u32) -> u32 {
    date_part(cell, DatePart::Month)
}

/// `Date.day(d)`.
#[no_mangle]
pub unsafe extern "C" fn __souther_date_day(cell: u32) -> u32 {
    date_part(cell, DatePart::Day)
}

/// `Date.fromParts(year, month, day)`, or the case those parts name no day.
#[no_mangle]
pub unsafe extern "C" fn __souther_date_from_parts(
    year: u32,
    month: u32,
    day: u32,
    absent: u32,
) -> u32 {
    let held_year = __souther_int_value(year);
    let held_month = __souther_int_value(month);
    let held_day = __souther_int_value(day);
    if held_month < 1
        || held_month > 12
        || held_day < 1
        || held_day > 31
        || held_year > 999_999_999
        || held_year < -999_999_999
        || !temporal::is_a_day(held_year, held_month as u32, held_day as u32)
    {
        return value::__souther_unit(absent);
    }
    temporal::made(
        value::TAG_DATE,
        temporal::days(held_year, held_month as u32, held_day as u32),
        0,
    )
}

/// `Time.fromParts(hour, minute, second)`, or the case those parts name no time of day.
#[no_mangle]
pub unsafe extern "C" fn __souther_time_from_parts(
    hour: u32,
    minute: u32,
    second: u32,
    absent: u32,
) -> u32 {
    let h = __souther_int_value(hour);
    let m = __souther_int_value(minute);
    let s = __souther_int_value(second);
    if !(0..=23).contains(&h) || !(0..=59).contains(&m) || !(0..=59).contains(&s) {
        return value::__souther_unit(absent);
    }
    temporal::made(value::TAG_TIME, 0, (h * 3600 + m * 60 + s) as i32)
}

enum TimePart {
    Hour,
    Minute,
    Second,
}

/// One walk over what a time is, which `Time.hour`, `Time.minute` and `Time.second` differ from in the last step.
unsafe fn time_part(cell: u32, part: TimePart) -> u32 {
    let held = temporal::second(cell) as i64;
    __souther_int(match part {
        TimePart::Hour => held / 3600,
        TimePart::Minute => held / 60 % 60,
        TimePart::Second => held % 60,
    })
}

/// `Time.hour(t)`.
#[no_mangle]
pub unsafe extern "C" fn __souther_time_hour(cell: u32) -> u32 {
    time_part(cell, TimePart::Hour)
}

/// `Time.minute(t)`.
#[no_mangle]
pub unsafe extern "C" fn __souther_time_minute(cell: u32) -> u32 {
    time_part(cell, TimePart::Minute)
}

/// `Time.second(t)`.
#[no_mangle]
pub unsafe extern "C" fn __souther_time_second(cell: u32) -> u32 {
    time_part(cell, TimePart::Second)
}

/// Moves a moment by `by` steps of `each` seconds.
unsafe fn datetime_add(by: u32, cell: u32, each: i64) -> u32 {
    let seconds = __souther_int_value(by) * each;
    let held = temporal::moment(cell) + seconds;
    let day = held.div_euclid(86_400);
    if day > i32::MAX as i64 || day < i32::MIN as i64 {
        abort(REASON_REQUIRED_FORM_HAS_NO_PLACE, 0, day as u64, 0);
    }
    temporal::made(value::TAG_DATE_TIME, day as i32, held.rem_euclid(86_400) as i32)
}

/// `DateTime.addMinutes(minutes, dt)`.
#[no_mangle]
pub unsafe extern "C" fn __souther_datetime_add_minutes(by: u32, cell: u32) -> u32 {
    datetime_add(by, cell, 60)
}

/// `DateTime.addHours(hours, dt)`.
#[no_mangle]
pub unsafe extern "C" fn __souther_datetime_add_hours(by: u32, cell: u32) -> u32 {
    datetime_add(by, cell, 3600)
}

/// `DateTime.minutesBetween(from, to)`, which counts whole minutes.
#[no_mangle]
pub unsafe extern "C" fn __souther_datetime_minutes_between(from: u32, to: u32) -> u32 {
    __souther_int((temporal::moment(to) - temporal::moment(from)) / 60)
}

/// `DateTime.toDate(dt)`.
#[no_mangle]
pub unsafe extern "C" fn __souther_datetime_to_date(cell: u32) -> u32 {
    temporal::made(value::TAG_DATE, temporal::day(cell), 0)
}

/// `DateTime.toTime(dt)`.
#[no_mangle]
pub unsafe extern "C" fn __souther_datetime_to_time(cell: u32) -> u32 {
    temporal::made(value::TAG_TIME, 0, temporal::second(cell))
}

/// `DateTime.fromDateAndTime(d, t)`.
#[no_mangle]
pub unsafe extern "C" fn __souther_datetime_from_parts(date: u32, time: u32) -> u32 {
    temporal::made(value::TAG_DATE_TIME, temporal::day(date), temporal::second(time))
}
