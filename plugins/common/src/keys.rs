// SPDX-FileCopyrightText: Lucas Greuloch
// SPDX-License-Identifier: AGPL-3.0-or-later

//! Which messages this process has already sent.
//!
//! Delivery is at-least-once: the core retries what it cannot confirm, and a
//! worker that crashed after the mail went out and before the outbox was marked
//! calls again with the same key. The contract says so in as many words and adds
//! the part that matters — *an implementation that cannot deduplicate says so in
//! its documentation; the core cannot make it safe from outside*.
//!
//! # What this promises, and what it does not
//!
//! In memory, bounded, newest kept. **A restart forgets**, and every plugin using
//! it says so in its `README` rather than implying otherwise. The window that
//! matters is minutes — the core's retry schedule — and a process lives longer
//! than that; a plugin with a durable store would be a plugin with a database,
//! and then a backup, and then a migration.
//!
//! A receiver that must not act twice checks the idempotency key itself. That is
//! the only place the guarantee can be made, and both plugins that use this say
//! so where a receiver will read it.

use std::collections::VecDeque;
use std::sync::Mutex;

/// A bounded set of the keys most recently delivered.
#[derive(Debug)]
pub struct Remembered {
    /// How many are kept.
    capacity: usize,
    /// The keys, oldest first.
    seen: Mutex<VecDeque<String>>,
}

impl Remembered {
    /// Creates one that keeps `capacity` keys.
    ///
    /// @param capacity how many to remember
    /// @return the set
    pub fn holding(capacity: usize) -> Self {
        Self {
            capacity,
            seen: Mutex::new(VecDeque::with_capacity(capacity.min(1024))),
        }
    }

    /// Whether this key has been seen, remembering it if not.
    ///
    /// An empty key is never a duplicate: a caller that sends none gets no
    /// deduplication rather than one bucket everything falls into.
    ///
    /// @param key the idempotency key
    /// @return whether it was already known
    pub fn already(&self, key: &str) -> bool {
        if key.is_empty() {
            return false;
        }
        let mut seen = self.seen.lock().expect("the key list is never poisoned");
        if seen.iter().any(|remembered| remembered == key) {
            return true;
        }
        if seen.len() >= self.capacity {
            seen.pop_front();
        }
        seen.push_back(key.to_string());
        false
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn a_key_is_remembered_once() {
        let keys = Remembered::holding(10);
        assert!(!keys.already("key-1"));
        assert!(keys.already("key-1"));
        assert!(!keys.already("key-2"));
    }

    #[test]
    fn an_empty_key_is_never_a_duplicate() {
        let keys = Remembered::holding(10);
        assert!(!keys.already(""));
        assert!(!keys.already(""));
    }

    #[test]
    fn the_oldest_key_falls_out_when_the_capacity_is_reached() {
        // Bounded, so a long-running process does not grow a list of every
        // message it has ever sent.
        let keys = Remembered::holding(2);
        keys.already("one");
        keys.already("two");
        keys.already("three");
        assert!(
            !keys.already("one"),
            "the oldest key should have been dropped"
        );
        assert!(keys.already("three"));
    }
}
