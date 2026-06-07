use crate::state::MediaItem;
use base64::Engine;
use chrono::{DateTime, Local, Utc};
use image::codecs::jpeg::JpegEncoder;
use serde::{Deserialize, Serialize};
use serde_json::{json, Value};
use std::collections::{hash_map::DefaultHasher, HashMap};
use std::fs;
use std::hash::{Hash, Hasher};
use std::io::BufWriter;
use std::path::{Path, PathBuf};

#[derive(Clone, Serialize, Deserialize, Debug, Default)]
pub(crate) struct MediaIndex {
    pub version: u32,
    pub items: Vec<MediaItem>,
}

#[derive(Clone, Debug)]
pub(crate) struct MediaSearch {
    pub limit: usize,
    pub offset: usize,
    pub kind: Option<String>,
    pub device: Option<String>,
    pub query: Option<String>,
    pub since_unix_ms: Option<u64>,
    pub until_unix_ms: Option<u64>,
    pub include_missing: bool,
    pub include_thumbnails: bool,
}

#[derive(Clone, Debug)]
pub(crate) struct MediaInsert {
    pub kind: String,
    pub path: PathBuf,
    pub timestamp: u64,
    pub size_bytes: u64,
    pub width: u32,
    pub height: u32,
    pub device_id: String,
    pub device_name: String,
    pub source_request_id: Option<String>,
    pub inline_image: Option<String>,
    pub capture_origin: String,
}

pub(crate) fn agent_slug(value: &str) -> String {
    let mut out = String::new();
    let mut previous_sep = false;
    for ch in value.chars().flat_map(|c| c.to_lowercase()) {
        if ch.is_ascii_alphanumeric() {
            out.push(ch);
            previous_sep = false;
        } else if !previous_sep && !out.is_empty() {
            out.push('_');
            previous_sep = true;
        }
    }
    while out.ends_with('_') {
        out.pop();
    }
    if out.is_empty() {
        "unknown_device".to_string()
    } else {
        out
    }
}

pub(crate) fn media_file_stem(
    kind: &str,
    timestamp_ms: u64,
    device_name: &str,
    discriminator: &str,
) -> String {
    let kind_slug = agent_slug(kind);
    let device_slug = agent_slug(device_name);
    let discriminator_slug = agent_slug(discriminator);
    format!(
        "captureport_{kind_slug}_unixms_{timestamp_ms}_device_{device_slug}_{discriminator_slug}"
    )
}

pub(crate) fn media_uri(id: &str) -> String {
    format!("captureport://media/{id}")
}

pub(crate) fn media_thumbnail_uri(id: &str) -> String {
    format!("captureport://media/{id}/thumbnail")
}

pub(crate) fn media_dir() -> PathBuf {
    std::env::var_os("CAPTUREPORT_MEDIA_DIR")
        .map(PathBuf::from)
        .unwrap_or_else(|| {
            crate::ws::handler::dirs::picture_dir()
                .unwrap_or_else(|| PathBuf::from("."))
                .join("CapturePort")
        })
}

pub(crate) fn media_data_dir() -> PathBuf {
    std::env::var_os("CAPTUREPORT_DATA_DIR")
        .map(PathBuf::from)
        .unwrap_or_else(|| {
            crate::state::dirs::data_dir()
                .unwrap_or_else(|| PathBuf::from("."))
                .join("CapturePort")
        })
}

#[allow(dead_code)]
pub(crate) fn thumbnail_dir() -> PathBuf {
    media_data_dir().join("thumbnails")
}

pub(crate) fn media_index_path() -> PathBuf {
    media_data_dir().join("media-index.json")
}

pub(crate) fn normalize_media_item(item: &mut MediaItem) {
    if item.mime_type.is_empty() {
        item.mime_type = mime_from_path(Path::new(&item.path))
            .unwrap_or_else(|| mime_from_kind(&item.kind))
            .to_string();
    }
    if item.label.is_empty() {
        let discriminator = item
            .source_request_id
            .as_deref()
            .filter(|id| !id.is_empty())
            .unwrap_or(&item.id);
        item.label = media_file_stem(&item.kind, item.timestamp, &item.device_name, discriminator);
    }
    if item.uri.is_empty() {
        item.uri = media_uri(&item.id);
    }
    if item.capture_origin.is_empty() {
        item.capture_origin = "unknown".to_string();
    }
    if item.notes.is_empty() {
        item.notes = media_note(item);
    }
}

pub(crate) fn media_summary(item: &MediaItem) -> Value {
    let timestamp_utc = timestamp_utc(item.timestamp);
    let timestamp_local = timestamp_local(item.timestamp);
    let age_seconds = (Utc::now().timestamp_millis() - item.timestamp as i64) / 1000;
    json!({
        "id": item.id.clone(),
        "uri": item.uri.clone(),
        "label": item.label.clone(),
        "kind": item.kind.clone(),
        "mime_type": item.mime_type.clone(),
        "timestamp_unix_ms": item.timestamp,
        "device_id": item.device_id.clone(),
        "device_name": item.device_name.clone(),
        "path": item.path.clone(),
        "size_bytes": item.size_bytes,
        "width": item.width,
        "height": item.height,
        "has_inline_image": item.base64_data.is_some(),
        "source_request_id": item.source_request_id.clone(),
        "timestamp_iso_utc": timestamp_utc,
        "captured_at_local": timestamp_local,
        "age_seconds": age_seconds,
        "thumbnail_uri": item.thumbnail_uri.clone(),
        "thumbnail_path": item.thumbnail_path.clone(),
        "preview": {
            "thumbnail_uri": item.thumbnail_uri.clone(),
            "thumbnail_path": item.thumbnail_path.clone(),
            "mime_type": if item.thumbnail_path.is_empty() { "" } else { "image/jpeg" },
            "available": !item.thumbnail_path.is_empty() && Path::new(&item.thumbnail_path).exists(),
            "resource_uri": item.thumbnail_uri.clone(),
        },
        "notes": item.notes.clone(),
        "capture_origin": item.capture_origin.clone(),
    })
}

pub(crate) fn hydrate_media_index() -> MediaIndex {
    hydrate_media_index_in_dirs(&media_dir(), &media_data_dir())
}

pub(crate) fn hydrate_media_index_in_dirs(media_root: &Path, data_root: &Path) -> MediaIndex {
    let mut index = load_media_index_from_path(&data_root.join("media-index.json"));
    let scanned = scan_captureport_media_dir(media_root);
    let mut by_path: HashMap<String, MediaItem> = HashMap::new();

    for mut item in index.items.drain(..) {
        normalize_media_item(&mut item);
        ensure_photo_thumbnail_in_data_dir(&mut item, data_root);
        by_path.insert(canonical_key(&item.path), item);
    }
    for mut item in scanned {
        normalize_media_item(&mut item);
        ensure_photo_thumbnail_in_data_dir(&mut item, data_root);
        by_path.insert(canonical_key(&item.path), item);
    }

    let mut items: Vec<MediaItem> = by_path
        .into_values()
        .filter(|item| Path::new(&item.path).exists())
        .collect();
    sort_media_newest_first(&mut items);
    let index = MediaIndex { version: 1, items };
    let _ = save_media_index_to_path(&data_root.join("media-index.json"), &index);
    index
}

#[allow(dead_code)]
pub(crate) fn load_media_index() -> MediaIndex {
    load_media_index_from_path(&media_index_path())
}

pub(crate) fn save_media_index(index: &MediaIndex) -> Result<(), String> {
    save_media_index_to_path(&media_index_path(), index)
}

fn load_media_index_from_path(path: &Path) -> MediaIndex {
    fs::read_to_string(path)
        .ok()
        .and_then(|content| serde_json::from_str::<MediaIndex>(&content).ok())
        .unwrap_or_else(|| MediaIndex {
            version: 1,
            items: Vec::new(),
        })
}

fn save_media_index_to_path(path: &Path, index: &MediaIndex) -> Result<(), String> {
    if let Some(parent) = path.parent() {
        fs::create_dir_all(parent).map_err(|e| e.to_string())?;
    }
    let mut stripped = index.clone();
    for item in &mut stripped.items {
        item.base64_data = None;
    }
    let content = serde_json::to_string_pretty(&stripped).map_err(|e| e.to_string())?;
    fs::write(path, content).map_err(|e| e.to_string())
}

pub(crate) fn scan_captureport_media_dir(root: &Path) -> Vec<MediaItem> {
    let mut items = Vec::new();
    let Ok(entries) = fs::read_dir(root) else {
        return items;
    };
    for entry in entries.flatten() {
        let path = entry.path();
        if !path.is_file() {
            continue;
        }
        let Some(kind) = kind_from_path(&path) else {
            continue;
        };
        let metadata = path.metadata().ok();
        let timestamp = parse_timestamp_from_path(&path).unwrap_or_else(|| {
            metadata
                .as_ref()
                .and_then(|m| m.modified().ok())
                .and_then(|t| t.duration_since(std::time::UNIX_EPOCH).ok())
                .map(|d| d.as_millis() as u64)
                .unwrap_or(0)
        });
        let device_name = parse_device_from_path(&path).unwrap_or_else(|| "Unknown".to_string());
        let id = stable_media_id(&path);
        let mut item = MediaItem {
            id: id.clone(),
            kind: kind.to_string(),
            path: path.to_string_lossy().to_string(),
            timestamp,
            size_bytes: metadata.as_ref().map(|m| m.len()).unwrap_or(0),
            width: 0,
            height: 0,
            base64_data: None,
            device_id: String::new(),
            device_name,
            label: path
                .file_stem()
                .map(|s| s.to_string_lossy().to_string())
                .unwrap_or_else(|| media_file_stem(kind, timestamp, "Unknown", &id)),
            uri: media_uri(&id),
            mime_type: mime_from_path(&path)
                .unwrap_or_else(|| mime_from_kind(kind))
                .to_string(),
            source_request_id: None,
            thumbnail_uri: String::new(),
            thumbnail_path: String::new(),
            notes: String::new(),
            capture_origin: "disk_scan".to_string(),
        };
        normalize_media_item(&mut item);
        items.push(item);
    }
    sort_media_newest_first(&mut items);
    items
}

pub(crate) fn register_media_item(
    state: &crate::state::AppState,
    insert: MediaInsert,
) -> Result<MediaItem, String> {
    let id = ulid::Ulid::new().to_string();
    let discriminator = insert
        .source_request_id
        .as_deref()
        .filter(|value| !value.is_empty())
        .unwrap_or(&id)
        .to_string();
    let mut item = MediaItem {
        id: id.clone(),
        kind: insert.kind.clone(),
        path: insert.path.to_string_lossy().to_string(),
        timestamp: insert.timestamp,
        size_bytes: insert.size_bytes,
        width: insert.width,
        height: insert.height,
        base64_data: insert.inline_image,
        device_id: insert.device_id,
        device_name: insert.device_name,
        label: media_file_stem(&insert.kind, insert.timestamp, "device", &discriminator),
        uri: media_uri(&id),
        mime_type: mime_from_path(&insert.path)
            .unwrap_or_else(|| mime_from_kind(&insert.kind))
            .to_string(),
        source_request_id: insert.source_request_id,
        thumbnail_uri: String::new(),
        thumbnail_path: String::new(),
        notes: String::new(),
        capture_origin: insert.capture_origin,
    };
    item.label = media_file_stem(
        &item.kind,
        item.timestamp,
        &item.device_name,
        &discriminator,
    );
    normalize_media_item(&mut item);

    if !crate::AppSettings::load().mcp_media_index_enabled() {
        return Ok(item);
    }

    ensure_photo_thumbnail_in_data_dir(&mut item, &media_data_dir());

    {
        let mut inner = state.inner.lock().unwrap();
        inner
            .media_history
            .retain(|existing| existing.path != item.path);
        inner.media_history.insert(0, item.clone());
        sort_media_newest_first(&mut inner.media_history);
    }

    let index = MediaIndex {
        version: 1,
        items: {
            let inner = state.inner.lock().unwrap();
            inner.media_history.clone()
        },
    };
    save_media_index(&index)?;
    Ok(item)
}

pub(crate) fn search_media_items(items: &[MediaItem], search: &MediaSearch) -> Vec<MediaItem> {
    let mut results: Vec<MediaItem> = items
        .iter()
        .filter(|item| {
            if !search.include_missing && !Path::new(&item.path).exists() {
                return false;
            }
            if let Some(kind) = &search.kind {
                if !item.kind.eq_ignore_ascii_case(kind) {
                    return false;
                }
            }
            if let Some(device) = &search.device {
                let needle = device.to_ascii_lowercase();
                if !item.device_id.to_ascii_lowercase().contains(&needle)
                    && !item.device_name.to_ascii_lowercase().contains(&needle)
                {
                    return false;
                }
            }
            if let Some(since) = search.since_unix_ms {
                if item.timestamp < since {
                    return false;
                }
            }
            if let Some(until) = search.until_unix_ms {
                if item.timestamp > until {
                    return false;
                }
            }
            if let Some(query) = &search.query {
                let haystack = format!(
                    "{} {} {} {} {} {}",
                    item.id,
                    item.uri,
                    item.label,
                    item.path,
                    item.device_name,
                    item.source_request_id.clone().unwrap_or_default()
                )
                .to_ascii_lowercase();
                if !haystack.contains(&query.to_ascii_lowercase()) {
                    return false;
                }
            }
            true
        })
        .cloned()
        .collect();
    sort_media_newest_first(&mut results);
    results
        .into_iter()
        .skip(search.offset)
        .take(search.limit.max(1))
        .map(|mut item| {
            if !search.include_thumbnails {
                item.base64_data = None;
            }
            item
        })
        .collect()
}

pub(crate) fn search_media(state: &crate::state::AppState, search: &MediaSearch) -> Vec<MediaItem> {
    let items = {
        let inner = state.inner.lock().unwrap();
        inner.media_history.clone()
    };
    search_media_items(&items, search)
}

pub(crate) fn latest_media(
    state: &crate::state::AppState,
    kind: Option<&str>,
) -> Option<MediaItem> {
    let search = MediaSearch {
        limit: 1,
        offset: 0,
        kind: kind.map(ToString::to_string),
        device: None,
        query: None,
        since_unix_ms: None,
        until_unix_ms: None,
        include_missing: false,
        include_thumbnails: true,
    };
    search_media(state, &search).into_iter().next()
}

pub(crate) fn find_media(
    state: &crate::state::AppState,
    id: Option<&str>,
    uri: Option<&str>,
) -> Option<MediaItem> {
    let inner = state.inner.lock().unwrap();
    inner
        .media_history
        .iter()
        .find(|item| {
            id.map(|id| item.id == id).unwrap_or(false)
                || uri
                    .map(|uri| item.uri == uri || media_uri(&item.id) == uri)
                    .unwrap_or(false)
        })
        .cloned()
}

pub(crate) fn read_photo_base64(item: &MediaItem) -> Option<String> {
    item.base64_data.clone().or_else(|| {
        fs::read(&item.path)
            .ok()
            .map(|bytes| base64::prelude::BASE64_STANDARD.encode(bytes))
    })
}

pub(crate) fn read_thumbnail_base64(item: &MediaItem) -> Option<String> {
    let mut item = item.clone();
    if item.thumbnail_path.is_empty() || !Path::new(&item.thumbnail_path).exists() {
        ensure_photo_thumbnail(&mut item);
    }
    fs::read(&item.thumbnail_path)
        .ok()
        .map(|bytes| base64::prelude::BASE64_STANDARD.encode(bytes))
}

pub(crate) fn ensure_photo_thumbnail(item: &mut MediaItem) -> bool {
    ensure_photo_thumbnail_in_data_dir(item, &media_data_dir())
}

fn ensure_photo_thumbnail_in_data_dir(item: &mut MediaItem, data_root: &Path) -> bool {
    if item.kind != "photo" || item.path.is_empty() {
        return false;
    }

    let thumbnail_root = data_root.join("thumbnails");
    let thumbnail_path = thumbnail_root.join(format!("{}.jpg", item.id));
    item.thumbnail_uri = media_thumbnail_uri(&item.id);
    item.thumbnail_path = thumbnail_path.to_string_lossy().to_string();

    if thumbnail_path.exists() {
        return true;
    }

    let Ok(image) = image::open(&item.path) else {
        return false;
    };
    if fs::create_dir_all(&thumbnail_root).is_err() {
        return false;
    }

    let thumbnail = image.thumbnail(320, 320).to_rgb8();
    let Ok(file) = fs::File::create(&thumbnail_path) else {
        return false;
    };
    let mut writer = BufWriter::new(file);
    JpegEncoder::new_with_quality(&mut writer, 78)
        .encode_image(&thumbnail)
        .is_ok()
}

pub(crate) fn compare_media_items(left: &MediaItem, right: &MediaItem) -> Value {
    let left_exists = Path::new(&left.path).exists();
    let right_exists = Path::new(&right.path).exists();
    let same_path = canonical_key(&left.path) == canonical_key(&right.path);
    let same_dimensions = left.width > 0
        && right.width > 0
        && left.width == right.width
        && left.height == right.height;
    let size_delta_bytes = left.size_bytes.abs_diff(right.size_bytes);
    let same_bytes = if left_exists && right_exists && left.size_bytes == right.size_bytes {
        match (fs::read(&left.path), fs::read(&right.path)) {
            (Ok(left_bytes), Ok(right_bytes)) => Some(left_bytes == right_bytes),
            _ => None,
        }
    } else {
        None
    };
    let score = if same_bytes == Some(true) || same_path {
        1.0
    } else {
        let dimension_score = if same_dimensions { 0.35 } else { 0.0 };
        let kind_score = if left.kind == right.kind { 0.2 } else { 0.0 };
        let size_score = if left.size_bytes > 0 && right.size_bytes > 0 {
            let max_size = left.size_bytes.max(right.size_bytes) as f64;
            let ratio = 1.0 - (size_delta_bytes as f64 / max_size).min(1.0);
            ratio * 0.35
        } else {
            0.0
        };
        let device_score = if !left.device_name.is_empty() && left.device_name == right.device_name
        {
            0.1
        } else {
            0.0
        };
        dimension_score + kind_score + size_score + device_score
    };

    json!({
        "left": media_summary(left),
        "right": media_summary(right),
        "same_path": same_path,
        "same_bytes": same_bytes,
        "same_dimensions": same_dimensions,
        "size_delta_bytes": size_delta_bytes,
        "similarity_score": (score * 1000.0).round() / 1000.0,
        "method": "metadata-and-byte-equality"
    })
}

fn sort_media_newest_first(items: &mut [MediaItem]) {
    items.sort_by(|a, b| b.timestamp.cmp(&a.timestamp).then_with(|| b.id.cmp(&a.id)));
}

fn stable_media_id(path: &Path) -> String {
    let mut hasher = DefaultHasher::new();
    canonical_key(&path.to_string_lossy()).hash(&mut hasher);
    format!("path_{:016x}", hasher.finish())
}

fn canonical_key(path: &str) -> String {
    let path = Path::new(path);
    path.canonicalize()
        .unwrap_or_else(|_| path.to_path_buf())
        .to_string_lossy()
        .to_ascii_lowercase()
}

fn kind_from_path(path: &Path) -> Option<&'static str> {
    match path
        .extension()?
        .to_string_lossy()
        .to_ascii_lowercase()
        .as_str()
    {
        "jpg" | "jpeg" | "png" => Some("photo"),
        "mp4" | "mov" => Some("video"),
        _ => None,
    }
}

fn mime_from_kind(kind: &str) -> &'static str {
    match kind {
        "video" => "video/mp4",
        _ => "image/jpeg",
    }
}

fn mime_from_path(path: &Path) -> Option<&'static str> {
    match path
        .extension()?
        .to_string_lossy()
        .to_ascii_lowercase()
        .as_str()
    {
        "jpg" | "jpeg" => Some("image/jpeg"),
        "png" => Some("image/png"),
        "mp4" => Some("video/mp4"),
        "mov" => Some("video/quicktime"),
        _ => None,
    }
}

fn parse_timestamp_from_path(path: &Path) -> Option<u64> {
    let stem = path.file_stem()?.to_string_lossy();
    let marker = "_unixms_";
    let start = stem.find(marker)? + marker.len();
    let rest = &stem[start..];
    let end = rest.find('_').unwrap_or(rest.len());
    rest[..end].parse::<u64>().ok()
}

fn parse_device_from_path(path: &Path) -> Option<String> {
    let stem = path.file_stem()?.to_string_lossy();
    let marker = "_device_";
    let start = stem.find(marker)? + marker.len();
    let rest = &stem[start..];
    let parts: Vec<&str> = rest.split('_').collect();
    if parts.len() > 2 {
        Some(parts[..parts.len() - 2].join("_"))
    } else if rest.is_empty() {
        None
    } else {
        Some(rest.to_string())
    }
}

fn timestamp_utc(timestamp_ms: u64) -> String {
    DateTime::<Utc>::from_timestamp_millis(timestamp_ms as i64)
        .unwrap_or_else(Utc::now)
        .to_rfc3339()
}

fn timestamp_local(timestamp_ms: u64) -> String {
    DateTime::<Utc>::from_timestamp_millis(timestamp_ms as i64)
        .unwrap_or_else(Utc::now)
        .with_timezone(&Local)
        .format("%Y-%m-%d %H:%M:%S %:z")
        .to_string()
}

fn media_note(item: &MediaItem) -> String {
    format!(
        "{} from {} captured at {} saved to {} ({}x{}, {} bytes).",
        item.kind,
        if item.device_name.is_empty() {
            "Unknown"
        } else {
            &item.device_name
        },
        timestamp_local(item.timestamp),
        item.path,
        item.width,
        item.height,
        item.size_bytes
    )
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::fs;
    use std::path::Path;

    fn sample_item() -> MediaItem {
        MediaItem {
            id: "01HXMEDIAITEM".to_string(),
            kind: "photo".to_string(),
            path: "C:\\Pictures\\CapturePort\\capture.jpg".to_string(),
            timestamp: 1712345678901,
            size_bytes: 42,
            width: 1920,
            height: 1080,
            base64_data: Some("abc".to_string()),
            device_id: "device-1".to_string(),
            device_name: "Pixel 8 Pro".to_string(),
            label: String::new(),
            uri: String::new(),
            mime_type: String::new(),
            source_request_id: Some("req-42".to_string()),
            thumbnail_uri: String::new(),
            thumbnail_path: String::new(),
            notes: String::new(),
            capture_origin: "mcp_look_camera".to_string(),
        }
    }

    #[test]
    fn media_file_stem_is_sortable_and_agent_readable() {
        assert_eq!(
            media_file_stem("photo", 1712345678901, "Pixel 8 Pro", "seq-42"),
            "captureport_photo_unixms_1712345678901_device_pixel_8_pro_seq_42"
        );
    }

    #[test]
    fn normalize_media_item_fills_agent_metadata() {
        let mut item = sample_item();
        normalize_media_item(&mut item);

        assert_eq!(item.uri, "captureport://media/01HXMEDIAITEM");
        assert_eq!(item.mime_type, "image/jpeg");
        assert_eq!(
            item.label,
            "captureport_photo_unixms_1712345678901_device_pixel_8_pro_req_42"
        );
    }

    #[test]
    fn media_summary_contains_agent_scan_fields() {
        let mut item = sample_item();
        normalize_media_item(&mut item);
        let summary = media_summary(&item);

        assert_eq!(summary["label"], item.label);
        assert_eq!(summary["timestamp_unix_ms"], 1712345678901u64);
        assert_eq!(summary["has_inline_image"], true);
        assert_eq!(summary["uri"], "captureport://media/01HXMEDIAITEM");
        assert!(summary["preview"].is_object());
    }

    #[test]
    fn ensure_photo_thumbnail_writes_preview_file_and_metadata() {
        let root = unique_test_dir("ensure_photo_thumbnail_writes_preview_file_and_metadata");
        let media_root = root.join("Pictures").join("CapturePort");
        let data_root = root.join("Data");
        fs::create_dir_all(&media_root).unwrap();
        fs::create_dir_all(&data_root).unwrap();
        let photo_path = media_root.join("photo.jpg");
        write_valid_jpeg(&photo_path);

        let mut item = sample_item();
        item.path = photo_path.to_string_lossy().to_string();
        item.thumbnail_path.clear();
        item.thumbnail_uri.clear();

        assert!(ensure_photo_thumbnail_in_data_dir(&mut item, &data_root));
        assert_eq!(
            item.thumbnail_uri,
            "captureport://media/01HXMEDIAITEM/thumbnail"
        );
        assert!(Path::new(&item.thumbnail_path).exists());

        let summary = media_summary(&item);
        assert_eq!(summary["preview"]["available"], true);
        assert_eq!(summary["preview"]["mime_type"], "image/jpeg");
    }

    #[test]
    fn search_media_items_filters_by_kind_device_query_and_time() {
        let mut first = sample_item();
        normalize_media_item(&mut first);
        let mut second = MediaItem {
            id: "01HXVIDEO".to_string(),
            kind: "video".to_string(),
            path: "C:\\Pictures\\CapturePort\\video.mp4".to_string(),
            timestamp: 1712345778901,
            size_bytes: 200,
            width: 1280,
            height: 720,
            base64_data: None,
            device_id: "device-2".to_string(),
            device_name: "Galaxy S24".to_string(),
            label: "captureport_video_unixms_1712345778901_device_galaxy_s24_req_9".to_string(),
            uri: "captureport://media/01HXVIDEO".to_string(),
            mime_type: "video/mp4".to_string(),
            source_request_id: Some("req-9".to_string()),
            thumbnail_uri: String::new(),
            thumbnail_path: String::new(),
            notes: String::new(),
            capture_origin: "mcp_record_video".to_string(),
        };
        normalize_media_item(&mut second);

        let results = search_media_items(
            &[first, second],
            &MediaSearch {
                limit: 10,
                offset: 0,
                kind: Some("video".to_string()),
                device: Some("galaxy".to_string()),
                query: Some("req_9".to_string()),
                since_unix_ms: Some(1712345700000),
                until_unix_ms: Some(1712345800000),
                include_missing: true,
                include_thumbnails: false,
            },
        );

        assert_eq!(results.len(), 1);
        assert_eq!(results[0].id, "01HXVIDEO");
    }

    #[test]
    fn hydrate_media_index_discovers_existing_photo_files() {
        let root = unique_test_dir("hydrate_media_index_discovers_existing_photo_files");
        let media_root = root.join("Pictures").join("CapturePort");
        let data_root = root.join("Data");
        fs::create_dir_all(&media_root).unwrap();
        fs::create_dir_all(&data_root).unwrap();
        write_minimal_jpeg(
            &media_root
                .join("captureport_photo_unixms_1712345678901_device_pixel_8_pro_old_file.jpg"),
        );

        let index = hydrate_media_index_in_dirs(&media_root, &data_root);

        assert_eq!(index.items.len(), 1);
        assert_eq!(index.items[0].kind, "photo");
        assert_eq!(index.items[0].timestamp, 1712345678901);
        assert_eq!(index.items[0].device_name, "pixel_8_pro");
        assert!(index.items[0].uri.starts_with("captureport://media/"));
        assert!(data_root.join("media-index.json").exists());
    }

    #[test]
    fn hydrate_media_index_preserves_png_mime_type() {
        let root = unique_test_dir("hydrate_media_index_preserves_png_mime_type");
        let media_root = root.join("Pictures").join("CapturePort");
        let data_root = root.join("Data");
        fs::create_dir_all(&media_root).unwrap();
        fs::create_dir_all(&data_root).unwrap();
        fs::write(
            media_root.join("captureport_photo_unixms_1712345678901_device_pixel_old.png"),
            [0x89, b'P', b'N', b'G'],
        )
        .unwrap();

        let index = hydrate_media_index_in_dirs(&media_root, &data_root);

        assert_eq!(index.items.len(), 1);
        assert_eq!(index.items[0].mime_type, "image/png");
    }

    #[test]
    fn media_summary_includes_human_fields_and_notes() {
        let mut item = sample_item();
        item.notes = "Photo from Pixel 8 Pro captured at local time.".to_string();
        item.capture_origin = "mcp_look_camera".to_string();
        normalize_media_item(&mut item);

        let summary = media_summary(&item);

        assert!(summary["timestamp_iso_utc"]
            .as_str()
            .unwrap()
            .starts_with("2024-04-05T"));
        assert!(summary["captured_at_local"]
            .as_str()
            .unwrap()
            .contains("2024"));
        assert!(summary["age_seconds"].as_i64().is_some());
        assert_eq!(summary["notes"], item.notes);
        assert_eq!(summary["capture_origin"], "mcp_look_camera");
    }

    #[test]
    fn compare_media_items_detects_identical_bytes() {
        let path =
            std::env::temp_dir().join(format!("captureport-compare-{}.jpg", ulid::Ulid::new()));
        fs::write(&path, [1, 2, 3, 4]).unwrap();

        let mut left = sample_item();
        left.path = path.to_string_lossy().to_string();
        left.size_bytes = 4;
        left.width = 10;
        left.height = 10;
        let mut right = left.clone();
        right.id = "01HXMEDIAITEM2".to_string();
        right.uri = media_uri(&right.id);
        normalize_media_item(&mut left);
        normalize_media_item(&mut right);

        let comparison = compare_media_items(&left, &right);
        assert_eq!(comparison["same_bytes"], true);
        assert_eq!(comparison["similarity_score"], 1.0);

        let _ = fs::remove_file(path);
    }

    fn unique_test_dir(name: &str) -> std::path::PathBuf {
        let dir = std::env::temp_dir().join(format!(
            "captureport_{name}_{}",
            std::time::SystemTime::now()
                .duration_since(std::time::UNIX_EPOCH)
                .unwrap()
                .as_nanos()
        ));
        let _ = fs::remove_dir_all(&dir);
        fs::create_dir_all(&dir).unwrap();
        dir
    }

    fn write_minimal_jpeg(path: &Path) {
        fs::write(path, [0xff, 0xd8, 0xff, 0xd9]).unwrap();
    }

    fn write_valid_jpeg(path: &Path) {
        let image = image::RgbImage::from_fn(12, 8, |x, y| {
            image::Rgb([(x * 10) as u8, (y * 20) as u8, 180])
        });
        let file = fs::File::create(path).unwrap();
        let mut writer = BufWriter::new(file);
        JpegEncoder::new_with_quality(&mut writer, 90)
            .encode_image(&image)
            .unwrap();
    }
}
