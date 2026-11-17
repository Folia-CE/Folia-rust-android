// Decoded covers for the wall, kept under a 48 MB budget. A request returns at once; workers decode it.
use std::collections::{HashSet, VecDeque};
use std::path::Path;
use std::sync::Arc;
use std::thread::JoinHandle;
use std::time::{Duration, Instant};

use folia_library::{CoverCache, cover_bytes};
use parking_lot::{Condvar, Mutex};

const COVER_BYTES: usize = 48 * 1024 * 1024;
const WORKERS: usize = 4;

#[derive(Clone, Debug, uniffi::Record)]
pub struct CoverAsk {
    pub key: String,
    pub path: String,
    pub url: String,
    pub width: i32,
    pub height: i32,
}

#[derive(Clone, Debug, uniffi::Enum)]
pub enum CoverLookup {
    Pending,
    Missing,
    // Encoded image bytes. The phone decodes them at the tile size.
    Ready { bytes: Vec<u8> },
}

#[derive(Clone)]
enum Cached {
    Missing,
    RetryAfter(Instant),
    Ready { bytes: Vec<u8> },
}

struct Queue {
    items: VecDeque<CoverAsk>,
    closed: bool,
}

type CoverFn = dyn Fn(&str, u32) -> Result<Vec<u8>, String> + Send + Sync;

pub(crate) struct CoverHost {
    cache: Arc<Mutex<CoverCache<String, Cached>>>,
    pending: Arc<Mutex<HashSet<String>>>,
    queue: Arc<(Mutex<Queue>, Condvar)>,
    workers: Vec<JoinHandle<()>>,
}

impl CoverHost {
    pub(crate) fn start(fetch_url: Arc<CoverFn>) -> Self {
        let cache = Arc::new(Mutex::new(CoverCache::new(COVER_BYTES)));
        let pending = Arc::new(Mutex::new(HashSet::new()));
        let queue = Arc::new((Mutex::new(Queue { items: VecDeque::new(), closed: false }), Condvar::new()));
        let mut workers = Vec::new();
        for index in 0..WORKERS {
            let cache = Arc::clone(&cache);
            let pending = Arc::clone(&pending);
            let queue = Arc::clone(&queue);
            let fetch_url = Arc::clone(&fetch_url);
            let spawned = std::thread::Builder::new().name(format!("folia-cover-{index}")).spawn(move || {
                loop {
                    let ask = {
                        let (lock, wake) = &*queue;
                        let mut guard = lock.lock();
                        while guard.items.is_empty() && !guard.closed {
                            wake.wait(&mut guard);
                        }
                        if guard.closed && guard.items.is_empty() {
                            return;
                        }
                        guard.items.pop_front()
                    };
                    let Some(ask) = ask else { continue };
                    let decoded = load_bytes(&ask, fetch_url.as_ref());
                    let bytes = match &decoded {
                        Cached::Ready { bytes } => bytes.len(),
                        Cached::Missing | Cached::RetryAfter(_) => 64,
                    };
                    cache.lock().insert(ask.key.clone(), Some(decoded), bytes);
                    pending.lock().remove(&ask.key);
                }
            });
            match spawned {
                Ok(handle) => workers.push(handle),
                Err(error) => tracing::error!(error = &error as &dyn std::error::Error, "cannot start a cover worker"),
            }
        }
        Self { cache, pending, queue, workers }
    }

    pub(crate) fn request(&self, asks: Vec<CoverAsk>) {
        self.enqueue(asks, false);
    }

    fn enqueue(&self, asks: Vec<CoverAsk>, playback: bool) {
        let mut queue = self.queue.0.lock();
        let mut pending = self.pending.lock();
        let cache = self.cache.lock();
        let mut added = false;
        for ask in asks {
            let needs_load = match cache.peek(&ask.key) {
                None => true,
                Some(Some(Cached::RetryAfter(when))) => Instant::now() >= *when,
                _ => false,
            };
            if ask.key.is_empty() || !needs_load || !pending.insert(ask.key.clone()) {
                continue;
            }
            if playback { queue.items.push_front(ask); } else { queue.items.push_back(ask); }
            added = true;
        }
        drop(cache);
        drop(pending);
        if added {
            self.queue.1.notify_all();
        }
    }

    pub(crate) fn lookup(&self, key: &str) -> CoverLookup {
        self.result(&key.to_owned(), false)
    }

    pub(crate) fn playback(&self, path: String, url: String) -> CoverLookup {
        if path.is_empty() && url.is_empty() { return CoverLookup::Missing; }
        let key = format!("playback:1024:{}:{path}{url}", path.len());
        self.enqueue(vec![CoverAsk { key: key.clone(), path, url, width: 1024, height: 1024 }], true);
        self.result(&key, true)
    }

    fn result(&self, key: &String, playback: bool) -> CoverLookup {
        let mut cache = self.cache.lock();
        let found = cache.peek(key).cloned();
        if matches!(found, Some(Some(Cached::Ready { .. }))) {
            cache.touch(key);
        }
        match found {
            Some(Some(Cached::Ready { bytes })) => CoverLookup::Ready { bytes },
            Some(Some(Cached::RetryAfter(_))) if playback => CoverLookup::Pending,
            Some(Some(Cached::Missing | Cached::RetryAfter(_))) | Some(None) => CoverLookup::Missing,
            None => CoverLookup::Pending,
        }
    }
}

impl Drop for CoverHost {
    fn drop(&mut self) {
        self.queue.0.lock().closed = true;
        self.queue.1.notify_all();
        for worker in self.workers.drain(..) {
            if worker.join().is_err() {
                tracing::error!("cover worker panicked");
            }
        }
    }
}

fn load_bytes(ask: &CoverAsk, fetch_url: &CoverFn) -> Cached {
    let edge = u32::try_from(ask.width.max(ask.height)).unwrap_or(1).max(1);
    if !ask.path.is_empty()
        && let Some(bytes) = cover_bytes(Path::new(&ask.path)).filter(|bytes| !bytes.is_empty())
    {
        return Cached::Ready { bytes };
    }
    if ask.url.is_empty() {
        return Cached::Missing;
    }
    match fetch_url(&ask.url, edge) {
        Ok(bytes) if !bytes.is_empty() => Cached::Ready { bytes },
        _ => Cached::RetryAfter(Instant::now() + Duration::from_secs(5)),
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn cached_audio_without_embedded_art_uses_url_and_network_failure_is_retryable() {
        let ask = CoverAsk { key: "track".into(), path: "/missing/audio.flac".into(),
            url: "cover".into(), width: 1024, height: 1024 };
        assert!(matches!(load_bytes(&ask, &|_, _| Err("offline".into())), Cached::RetryAfter(_)));
        assert!(matches!(load_bytes(&ask, &|url, edge| {
            assert_eq!((url, edge), ("cover", 1024));
            Ok(vec![1, 2, 3])
        }), Cached::Ready { bytes } if bytes == [1, 2, 3]));
    }
}
