// SPDX-FileCopyrightText: Lucas Greuloch
// SPDX-License-Identifier: AGPL-3.0-or-later

//! The calendar, from a count of seconds, without a date crate.
//!
//! Two plugins need a UTC date and they need two different spellings of it:
//! `plugins/smtp/` writes an RFC 5322 `Date:` header, `plugins/blobstore-s3/`
//! writes `YYYYMMDDTHHMMSSZ` into every signature and `YYYYMMDD` into its
//! credential scope. Both are the same twenty lines of arithmetic underneath,
//! which is why they are here rather than in whichever plugin happened to need
//! one first ([ADR-0072]).
//!
//! UTC only, deliberately. These containers carry no time zone database, and
//! neither caller wants a local zone: a signature is defined over UTC, and a
//! mail client shows the recipient's own zone whatever the header says.
//!
//! [ADR-0072]: ../../../docs/adr/0072-first-party-plugins-live-here.md

/// Seconds in a day.
pub const DAY: u64 = 86_400;

/// Days since the epoch to a civil date.
///
/// Howard Hinnant's `civil_from_days`, which is the algorithm every date library
/// uses and is twenty lines. Correct for every date after 1970, which is every
/// date this will ever be asked about.
///
/// # Arguments
///
/// * `days` — days since 1970-01-01
///
/// # Returns
///
/// The year, the month (1–12) and the day of the month (1–31).
pub fn civil_from_days(days: i64) -> (i64, u32, u32) {
    let z = days + 719_468;
    let era = z.div_euclid(146_097);
    let doe = z.rem_euclid(146_097);
    let yoe = (doe - doe / 1460 + doe / 36524 - doe / 146_096) / 365;
    let year = yoe + era * 400;
    let doy = doe - (365 * yoe + yoe / 4 - yoe / 100);
    let mp = (5 * doy + 2) / 153;
    let day = (doy - (153 * mp + 2) / 5 + 1) as u32;
    let month = if mp < 10 { mp + 3 } else { mp - 9 } as u32;
    (if month <= 2 { year + 1 } else { year }, month, day)
}

/// The two stamps an AWS Signature Version 4 request carries.
///
/// # Arguments
///
/// * `seconds` — seconds since the epoch
///
/// # Returns
///
/// `(YYYYMMDD, YYYYMMDDTHHMMSSZ)` — the first is the credential scope's date,
/// the second is the `x-amz-date` header. They have to agree, which is why one
/// call produces both: two calls either side of midnight would produce a request
/// that signs one day and claims the other, and the rejection names neither.
pub fn amz_stamps(seconds: u64) -> (String, String) {
    let (year, month, day) = civil_from_days((seconds / DAY) as i64);
    let time = seconds % DAY;
    let date = format!("{year:04}{month:02}{day:02}");
    let stamp = format!(
        "{date}T{:02}{:02}{:02}Z",
        time / 3600,
        (time % 3600) / 60,
        time % 60
    );
    (date, stamp)
}

/// The seconds since the epoch, now.
///
/// Before 1970 the clock is broken rather than early, and a signature stamped
/// with a saturated zero is refused by the far end with a message about clock
/// skew — which is the truth.
pub fn now() -> u64 {
    std::time::SystemTime::now()
        .duration_since(std::time::UNIX_EPOCH)
        .map(|since| since.as_secs())
        .unwrap_or(0)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn the_epoch_itself() {
        assert_eq!(civil_from_days(0), (1970, 1, 1));
    }

    #[test]
    fn a_leap_day() {
        // 2024-02-29 is day 19 782, and a date routine that gets this wrong gets
        // one day in 1 461 wrong, which is the kind of defect that is found in
        // February.
        assert_eq!(civil_from_days(19_782), (2024, 2, 29));
    }

    #[test]
    fn the_stamps_agree_and_are_utc() {
        // 2023-11-14T22:13:20Z, the timestamp the webhook tests also use.
        let (date, stamp) = amz_stamps(1_700_000_000);
        assert_eq!(date, "20231114");
        assert_eq!(stamp, "20231114T221320Z");
        assert!(stamp.starts_with(&date));
    }

    #[test]
    fn midnight_is_the_first_second_of_the_new_day() {
        let (date, stamp) = amz_stamps(1_700_000_000 - 22 * 3600 - 13 * 60 - 20);
        assert_eq!(date, "20231114");
        assert_eq!(stamp, "20231114T000000Z");
    }
}
