//! A day, a time of day, and the two together.
//!
//! A day is held as how many days it is from the first of January nineteen seventy, and a time of
//! day as how many seconds it is past midnight. Both are one number, so a difference is a
//! subtraction and an ordering is a comparison — and what a day is called in a calendar is worked
//! out from the number where something asks.
//!
//! ```text
//! +0  u32 tag
//! +4  i32 the day, counted from the first of January nineteen seventy
//! +8  i32 the second past midnight
//! ```
//!
//! A `Date` leaves the second at nothing and a `Time` leaves the day at nothing, so the three are
//! one shape and only what is read off it differs.

use crate::{abort, alloc, REASON_OUT_OF_RANGE};

const OFF_DAY: usize = 4;
const OFF_SECOND: usize = 8;
const HEADER: u32 = 12;

/// Seconds in a day.
const A_DAY: i64 = 86_400;

/// A day, a time or the two together.
pub unsafe fn made(tag: u32, day: i32, second: i32) -> u32 {
    let cell = alloc(HEADER);
    core::ptr::write_unaligned(cell as usize as *mut u32, tag);
    core::ptr::write_unaligned((cell as usize + OFF_DAY) as *mut i32, day);
    core::ptr::write_unaligned((cell as usize + OFF_SECOND) as *mut i32, second);
    cell
}

/// Which day it is, counted from the first of January nineteen seventy.
pub unsafe fn day(cell: u32) -> i32 {
    core::ptr::read_unaligned((cell as usize + OFF_DAY) as *const i32)
}

/// How many seconds past midnight.
pub unsafe fn second(cell: u32) -> i32 {
    core::ptr::read_unaligned((cell as usize + OFF_SECOND) as *const i32)
}

/// The year, month and day a count of days falls on.
///
/// Counted from March so that a leap day falls at the end of a year rather than in the middle of
/// one, which is what makes the arithmetic a handful of divisions instead of a table.
pub fn civil(days: i32) -> (i64, u32, u32) {
    let z = days as i64 + 719_468;
    let era = if z >= 0 { z } else { z - 146_096 } / 146_097;
    let day_of_era = z - era * 146_097;
    let year_of_era =
        (day_of_era - day_of_era / 1460 + day_of_era / 36524 - day_of_era / 146_096) / 365;
    let year = year_of_era + era * 400;
    let day_of_year = day_of_era - (365 * year_of_era + year_of_era / 4 - year_of_era / 100);
    let months = (5 * day_of_year + 2) / 153;
    let day = (day_of_year - (153 * months + 2) / 5 + 1) as u32;
    let month = if months < 10 { months + 3 } else { months - 9 } as u32;
    (if month <= 2 { year + 1 } else { year }, month, day)
}

/// Which day a year, month and day is, counted the same way.
pub fn days(year: i64, month: u32, day: u32) -> i32 {
    let y = if month <= 2 { year - 1 } else { year };
    let era = if y >= 0 { y } else { y - 399 } / 400;
    let year_of_era = y - era * 400;
    let months = if month > 2 { month - 3 } else { month + 9 } as i64;
    let day_of_year = (153 * months + 2) / 5 + day as i64 - 1;
    let day_of_era = year_of_era * 365 + year_of_era / 4 - year_of_era / 100 + day_of_year;
    (era * 146_097 + day_of_era - 719_468) as i32
}

/// How many days a month of a year has.
pub fn month_length(year: i64, month: u32) -> u32 {
    match month {
        1 | 3 | 5 | 7 | 8 | 10 | 12 => 31,
        4 | 6 | 9 | 11 => 30,
        _ => {
            if (year % 4 == 0 && year % 100 != 0) || year % 400 == 0 {
                29
            } else {
                28
            }
        }
    }
}

/// Whether a year, month and day names a day there is.
pub fn is_a_day(year: i64, month: u32, day: u32) -> bool {
    (1..=12).contains(&month) && day >= 1 && day <= month_length(year, month)
}

/// The same day of a later or earlier month, kept inside the month it lands in.
///
/// A month has as many days as it has, so the last of January a month later is the last of
/// February and not the first of March.
pub fn moved_by_months(days_from_epoch: i32, by: i64) -> i32 {
    let (year, month, day) = civil(days_from_epoch);
    let months = year * 12 + (month as i64 - 1) + by;
    let held_year = months.div_euclid(12);
    let held_month = (months.rem_euclid(12) + 1) as u32;
    let length = month_length(held_year, held_month);
    days(held_year, held_month, if day > length { length } else { day })
}

/// A day as a calendar writes one.
pub unsafe fn written_day(cell: u32) -> (u32, u32) {
    let (year, month, day) = civil(day(cell));
    let out = crate::next_free();
    let mut total = year_written(year);
    total += byte(b'-');
    total += two(month);
    total += byte(b'-');
    total += two(day);
    (out, total)
}

/// A time of day as a clock writes one, leaving the seconds out where there are none.
pub unsafe fn written_time(cell: u32) -> (u32, u32) {
    let held = second(cell);
    let out = crate::next_free();
    let mut total = two((held / 3600) as u32);
    total += byte(b':');
    total += two((held / 60 % 60) as u32);
    if held % 60 != 0 {
        total += byte(b':');
        total += two((held % 60) as u32);
    }
    (out, total)
}

/// A day and a time of day, with the one letter between them that says which is which.
pub unsafe fn written_both(cell: u32) -> (u32, u32) {
    let out = crate::next_free();
    let (_, day_length) = written_day(cell);
    let mut total = day_length;
    total += byte(b'T');
    let (_, time_length) = written_time(cell);
    total += time_length;
    (out, total)
}

unsafe fn year_written(year: i64) -> u32 {
    // Four digits where four are enough, and a sign in front where they are not — which is what a
    // calendar does with a year outside the four it usually writes.
    if (0..=9999).contains(&year) {
        return four(year as u32);
    }
    let mut total = byte(if year < 0 { b'-' } else { b'+' });
    let magnitude = if year < 0 { -year } else { year } as u64;
    let mut room = [0u8; 20];
    let mut places = 0;
    let mut rest = magnitude;
    while rest > 0 {
        room[places] = b'0' + (rest % 10) as u8;
        places += 1;
        rest /= 10;
    }
    while places < 4 {
        room[places] = b'0';
        places += 1;
    }
    for i in (0..places).rev() {
        total += byte(room[i]);
    }
    total
}

unsafe fn four(value: u32) -> u32 {
    let mut total = 0;
    for place in [1000, 100, 10, 1] {
        total += byte(b'0' + (value / place % 10) as u8);
    }
    total
}

unsafe fn two(value: u32) -> u32 {
    byte(b'0' + (value / 10) as u8) + byte(b'0' + (value % 10) as u8)
}

unsafe fn byte(held: u8) -> u32 {
    let at = alloc(1);
    core::ptr::write(at as *mut u8, held);
    1
}

/// Reads a day as a calendar writes one, or answers nothing.
pub unsafe fn read_day(at: u32, length: u32) -> Option<i32> {
    let mut i = 0;
    let mut year: i64 = 0;
    let signed = length > 0 && (byte_at(at, 0) == b'-' || byte_at(at, 0) == b'+');
    let negative = signed && byte_at(at, 0) == b'-';
    if signed {
        i = 1;
    }
    let start = i;
    while i < length && byte_at(at, i).is_ascii_digit() {
        year = year * 10 + (byte_at(at, i) - b'0') as i64;
        if year > 999_999_999 {
            return None;
        }
        i += 1;
    }
    let places = i - start;
    if (!signed && places != 4) || (signed && places < 4) {
        return None;
    }
    if negative {
        year = -year;
    }
    if i + 6 != length || byte_at(at, i) != b'-' || byte_at(at, i + 3) != b'-' {
        return None;
    }
    let month = two_at(at, i + 1)?;
    let day = two_at(at, i + 4)?;
    if !is_a_day(year, month, day) {
        return None;
    }
    Some(days(year, month, day))
}

/// Reads a time of day as a clock writes one, or answers nothing.
pub unsafe fn read_time(at: u32, length: u32) -> Option<i32> {
    if length != 5 && length != 8 {
        return None;
    }
    if byte_at(at, 2) != b':' || (length == 8 && byte_at(at, 5) != b':') {
        return None;
    }
    let hour = two_at(at, 0)?;
    let minute = two_at(at, 3)?;
    let second = if length == 8 { two_at(at, 6)? } else { 0 };
    if hour > 23 || minute > 59 || second > 59 {
        return None;
    }
    Some((hour * 3600 + minute * 60 + second) as i32)
}

/// Reads a day and a time of day together, or answers nothing.
pub unsafe fn read_both(at: u32, length: u32) -> Option<(i32, i32)> {
    for i in 0..length {
        if byte_at(at, i) == b'T' {
            let day = read_day(at, i)?;
            let second = read_time(at + i + 1, length - i - 1)?;
            return Some((day, second));
        }
    }
    None
}

unsafe fn byte_at(at: u32, index: u32) -> u8 {
    core::ptr::read((at + index) as *const u8)
}

unsafe fn two_at(at: u32, index: u32) -> Option<u32> {
    let first = byte_at(at, index);
    let held = byte_at(at, index + 1);
    if !first.is_ascii_digit() || !held.is_ascii_digit() {
        return None;
    }
    Some(((first - b'0') * 10 + (held - b'0')) as u32)
}

/// A moment on the timeline: how many seconds it is from the start of nineteen seventy, and how
/// many nanoseconds past that second.
///
/// ```text
/// +0  u32 tag
/// +4  i64 the second, counted from the start of nineteen seventy
/// +12 i32 the nanosecond past it
/// ```
///
/// Wider than a day and a time because it holds what an outside timestamp said, down to the
/// nanosecond. Dropping that would make a value written back out differ from the one that arrived.
pub unsafe fn moment_made(second: i64, nanosecond: i32) -> u32 {
    let cell = alloc(MOMENT_HEADER);
    core::ptr::write_unaligned(cell as usize as *mut u32, crate::value::TAG_INSTANT);
    core::ptr::write_unaligned((cell as usize + OFF_MOMENT) as *mut i64, second);
    core::ptr::write_unaligned((cell as usize + OFF_NANO) as *mut i32, nanosecond);
    cell
}

const OFF_MOMENT: usize = 4;
const OFF_NANO: usize = 12;
const MOMENT_HEADER: u32 = 16;

/// Which second of the timeline a moment is.
pub unsafe fn moment_second(cell: u32) -> i64 {
    core::ptr::read_unaligned((cell as usize + OFF_MOMENT) as *const i64)
}

/// How many nanoseconds past that second.
pub unsafe fn moment_nano(cell: u32) -> i32 {
    core::ptr::read_unaligned((cell as usize + OFF_NANO) as *const i32)
}

/// A moment as a timestamp writes one: the calendar reading it has in no zone but the one it is
/// counted from, and a `Z` saying so.
///
/// The seconds are always there — a clock's own form leaves `:00` out and this one does not, and a
/// moment is not a time of day. A fraction is written to three, six or nine places, whichever is
/// the fewest that says the whole of it: a reading written to a different number of places is a
/// different string for the same moment.
pub unsafe fn written_moment(cell: u32) -> (u32, u32) {
    let held = moment_second(cell);
    let nano = moment_nano(cell);
    let days = held.div_euclid(A_DAY) as i32;
    let past = held.rem_euclid(A_DAY) as i32;
    let (year, month, day) = civil(days);
    let out = crate::next_free();
    let mut total = year_written(year);
    total += byte(b'-');
    total += two(month);
    total += byte(b'-');
    total += two(day);
    total += byte(b'T');
    total += two((past / 3600) as u32);
    total += byte(b':');
    total += two((past / 60 % 60) as u32);
    total += byte(b':');
    total += two((past % 60) as u32);
    if nano != 0 {
        total += byte(b'.');
        let places = if nano % 1_000_000 == 0 {
            3
        } else if nano % 1_000 == 0 {
            6
        } else {
            9
        };
        let mut divisor = 100_000_000;
        for _ in 0..places {
            total += byte(b'0' + (nano / divisor % 10) as u8);
            divisor /= 10;
        }
    }
    total += byte(b'Z');
    (out, total)
}

/// Reads a moment as a timestamp writes one, or answers nothing.
///
/// An offset is a different spelling of the same moment and is taken; what it names is moved to the
/// one this counts from. A leap second is not a moment the timeline has, and taking it would put a
/// value here that says a different second than the text did, so it is refused.
pub unsafe fn read_moment(at: u32, length: u32) -> Option<(i64, i32)> {
    let mut i = 0;
    while i < length && byte_at(at, i) | 0x20 != b't' {
        i += 1;
    }
    if i >= length {
        return None;
    }
    let day = read_day(at, i)?;
    let mut rest = i + 1;
    // The zone comes off the end first: what is left in front of it is a reading of a clock.
    let (offset, ends) = zone(at, length, rest)?;
    if ends < rest + 5 {
        return None;
    }
    if byte_at(at, rest + 2) != b':' {
        return None;
    }
    let hour = two_at(at, rest)?;
    let minute = two_at(at, rest + 2 + 1)?;
    rest += 5;
    let mut second = 0;
    let mut nano = 0;
    if rest < ends {
        if byte_at(at, rest) != b':' {
            return None;
        }
        second = two_at(at, rest + 1)?;
        rest += 3;
        if rest < ends {
            if byte_at(at, rest) != b'.' {
                return None;
            }
            rest += 1;
            let mut places = 0;
            while rest < ends && byte_at(at, rest).is_ascii_digit() {
                nano = nano * 10 + (byte_at(at, rest) - b'0') as i32;
                places += 1;
                rest += 1;
            }
            // A point with nothing after it says no fraction rather than a broken one, and a
            // tenth place past the nanosecond says something a moment cannot hold.
            if places > 9 || rest != ends {
                return None;
            }
            for _ in places..9 {
                nano *= 10;
            }
            if places == 0 {
                nano = 0;
            }
        }
    }
    // A clock reading of twenty-four is the end of the day rather than an hour of it, and only
    // where nothing has happened past it — that is what the form this reads says, and a moment
    // named that way is the first of the next day.
    let midnight = hour == 24 && minute == 0 && second == 0 && nano == 0;
    if (hour > 23 && !midnight) || minute > 59 || second > 59 {
        return None;
    }
    let held = day as i64 * A_DAY + (hour * 3600 + minute * 60 + second) as i64 - offset;
    Some((held, nano))
}

/// What the end of a moment's text says about where it was read, and where that text ends.
///
/// Answers how many seconds the reading is ahead of what the timeline counts from, so taking it
/// off leaves the moment itself.
unsafe fn zone(at: u32, length: u32, from: u32) -> Option<(i64, u32)> {
    if length == 0 {
        return None;
    }
    let last = byte_at(at, length - 1);
    if last | 0x20 == b'z' {
        return Some((0, length - 1));
    }
    // An offset is five or six bytes: a sign, two digits, and the minutes with or without a colon.
    for width in [6u32, 5] {
        if length < from + width {
            continue;
        }
        let start = length - width;
        let sign = byte_at(at, start);
        if sign != b'+' && sign != b'-' {
            continue;
        }
        if width == 6 && byte_at(at, start + 3) != b':' {
            continue;
        }
        let hours = two_at(at, start + 1)?;
        let minutes = two_at(at, start + width - 2)?;
        if hours > 18 || minutes > 59 {
            return None;
        }
        let held = (hours * 3600 + minutes * 60) as i64;
        return Some((if sign == b'-' { -held } else { held }, start));
    }
    None
}

/// A day so many days later, or an end to the call where that is not a day a calendar reaches.
pub unsafe fn moved(days_from_epoch: i32, by: i64) -> i32 {
    let held = days_from_epoch as i64 + by;
    if held > i32::MAX as i64 || held < i32::MIN as i64 {
        abort(REASON_OUT_OF_RANGE, 0, held as u64, 0);
    }
    held as i32
}

/// The tags a day, a time and the two together are held under, so that what reads one can say
/// which it is looking at.
pub unsafe fn tag_of(cell: u32) -> u32 {
    core::ptr::read_unaligned(cell as usize as *const u32)
}

/// How many seconds a day and a time together is from the start of nineteen seventy.
pub unsafe fn moment(cell: u32) -> i64 {
    day(cell) as i64 * A_DAY + second(cell) as i64
}
