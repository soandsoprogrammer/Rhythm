// SPDX-License-Identifier: GPL-3.0-or-later
import { createHash, createHmac, randomUUID, timingSafeEqual } from 'node:crypto';
import { createReadStream } from 'node:fs';
import { open, readFile, readdir, realpath, rename, stat, writeFile } from 'node:fs/promises';
import { createServer } from 'node:http';
import { basename, extname, isAbsolute, join, relative, resolve, sep } from 'node:path';

const root = await realpath(process.argv[2] || process.env.SHENYE_MUSIC_DIR || './music');
const host = process.env.SHENYE_HOST || '127.0.0.1';
const port = Number(process.env.SHENYE_PORT || 8787);
const username = process.env.SHENYE_USER || 'music';
const token = process.env.SHENYE_TOKEN;
const statePath = resolve(process.env.SHENYE_STATE || './.shenye-state.json');
if (!token || token.length < 16) throw new Error('Set SHENYE_TOKEN to a unique token of at least 16 characters.');
if (!Number.isInteger(port) || port < 1 || port > 65535) throw new Error('Invalid SHENYE_PORT.');

const formats = {
    flac: 'audio/flac', mp3: 'audio/mpeg', wav: 'audio/wav', m4a: 'audio/mp4',
    aac: 'audio/aac', ogg: 'audio/ogg', opus: 'audio/ogg',
    jpg: 'image/jpeg', jpeg: 'image/jpeg', png: 'image/png', webp: 'image/webp'
};
const audioTypes = new Set(['flac', 'mp3', 'wav', 'm4a', 'aac', 'ogg', 'opus']);
const mutations = new Set(['star', 'unstar', 'createPlaylist', 'updatePlaylist', 'deletePlaylist', 'scrobble', 'reportPlayback']);
const stableId = (kind, name) => kind + '-' + createHash('sha256').update(name).digest('hex').slice(0, 32);
const sortNames = (a, b) => a.localeCompare(b, 'zh-CN', { numeric: true });
const inside = path => {
    const nested = relative(root, path);
    return nested === '' || (!isAbsolute(nested) && nested !== '..' && !nested.startsWith('..' + sep));
};
const lookup = async path => {
    try {
        const actual = await realpath(path);
        return inside(actual) && (await stat(actual)).isFile() ? actual : null;
    } catch (error) {
        if (error.code === 'ENOENT' || error.code === 'ENOTDIR') return null;
        throw error;
    }
};

async function audioProperties(file, suffix) {
    if (suffix !== 'flac' && suffix !== 'wav') return {};
    const handle = await open(file);
    try {
        const buffer = Buffer.alloc(65536);
        const { bytesRead } = await handle.read(buffer, 0, buffer.length, 0);
        if (suffix === 'flac' && bytesRead >= 42 && buffer.toString('ascii', 0, 4) === 'fLaC') {
            const fields = buffer.readBigUInt64BE(18);
            const sampleRate = Number(fields >> 44n);
            const channels = Number((fields >> 41n) & 7n) + 1;
            const samples = Number(fields & ((1n << 36n) - 1n));
            return sampleRate ? { sampleRate, channels, duration: samples / sampleRate } : {};
        }
        if (suffix !== 'wav' || bytesRead < 44 || buffer.toString('ascii', 8, 12) !== 'WAVE') return {};
        let properties = {};
        let bytesPerSecond = 0;
        for (let offset = 12; offset + 8 <= bytesRead;) {
            const kind = buffer.toString('ascii', offset, offset + 4);
            const size = buffer.readUInt32LE(offset + 4);
            const start = offset + 8;
            if (kind === 'fmt ' && size >= 16 && start + 16 <= bytesRead) {
                properties = { channels: buffer.readUInt16LE(start + 2), sampleRate: buffer.readUInt32LE(start + 4) };
                bytesPerSecond = buffer.readUInt32LE(start + 8);
            }
            if (kind === 'data' && bytesPerSecond) return { ...properties, duration: size / bytesPerSecond };
            offset = start + size + size % 2;
        }
        return properties;
    } finally {
        await handle.close();
    }
}

async function readLyrics(track) {
    if (!track) return '';
    const sidecar = await lookup(track.lrc);
    if (sidecar && (await stat(sidecar)).size <= 1_048_576) return await readFile(sidecar, 'utf8');
    if (track.song.suffix !== 'flac') return '';
    const audio = await lookup(track.file);
    if (!audio) return '';
    const handle = await open(audio);
    try {
        const header = Buffer.alloc(4);
        let offset = 4;
        while (offset < 16_777_216 && (await handle.read(header, 0, 4, offset)).bytesRead === 4) {
            const type = header[0] & 127;
            const length = header.readUIntBE(1, 3);
            if (type === 4 && length >= 8 && length <= 1_048_576) {
                const block = Buffer.alloc(length);
                if ((await handle.read(block, 0, length, offset + 4)).bytesRead !== length) return '';
                let cursor = 4 + block.readUInt32LE(0);
                if (cursor + 4 > length) return '';
                const count = block.readUInt32LE(cursor);
                cursor += 4;
                for (let i = 0; i < count && cursor + 4 <= length; i++) {
                    const tagSize = block.readUInt32LE(cursor);
                    cursor += 4;
                    if (cursor + tagSize > length) return '';
                    const tag = block.toString('utf8', cursor, cursor + tagSize);
                    if (tag.slice(0, 7).toUpperCase() === 'LYRICS=') return tag.slice(7);
                    cursor += tagSize;
                }
                return '';
            }
            if (header[0] & 128) return '';
            offset += 4 + length;
        }
        return '';
    } finally {
        await handle.close();
    }
}

async function scanLibrary() {
    const songs = new Map();
    const albums = new Map();
    const artists = new Map();
    let modified = 0;
    async function walk(folder) {
        const entries = (await readdir(folder, { withFileTypes: true })).sort((a, b) => sortNames(a.name, b.name));
        const folderKey = relative(root, folder);
        const parts = folderKey ? folderKey.split(sep) : [];
        const artistName = parts.length < 2 ? '未知艺术家' : parts[0];
        const albumName = parts.at(-1) || '音乐收藏';
        const artistId = stableId('artist', artistName);
        const albumId = stableId('album', folderKey);
        const image = entries.find(entry => entry.isFile() && /^(cover|folder)\.(jpe?g|png|webp)$/i.test(entry.name));
        const cover = image ? join(folder, image.name) : null;
        for (const entry of entries) {
            const file = join(folder, entry.name);
            if (entry.isDirectory()) {
                await walk(file);
                continue;
            }
            const extension = extname(entry.name);
            const suffix = extension.slice(1).toLowerCase();
            if (!entry.isFile() || !audioTypes.has(suffix)) continue;

            const fileInfo = await stat(file);
            modified = Math.max(modified, fileInfo.mtimeMs);
            const id = stableId('song', relative(root, file));
            const title = basename(entry.name, extension);
            const track = Number(title.match(/^(\d+)[ ._-]/)?.[1]) || 0;
            const song = {
                id, title, artist: artistName, artistId, album: albumName, albumId, albumArtist: artistName,
                suffix, track, contentType: formats[suffix], size: fileInfo.size,
                coverArt: cover ? albumId : undefined, ...await audioProperties(file, suffix)
            };
            songs.set(id, { song, file, lrc: join(folder, title + '.lrc') });
            if (!albums.has(albumId)) {
                albums.set(albumId, { id: albumId, name: albumName, artist: artistName, artistId,
                    coverArt: cover ? albumId : undefined, cover, songIds: [], modified: 0 });
            }
            const album = albums.get(albumId);
            album.songIds.push(id);
            album.modified = Math.max(album.modified, fileInfo.mtimeMs);
            if (!artists.has(artistId)) artists.set(artistId, { id: artistId, name: artistName, albumIds: new Set() });
            artists.get(artistId).albumIds.add(albumId);
        }
    }
    await walk(root);
    const marker = createHash('sha256').update([...songs.keys()].join('\n') + ':' + modified).digest('hex');
    return { songs, albums, artists, modified: Math.floor(modified), marker };
}

let library;
let refreshed = 0;
let scan;
async function getLibrary() {
    if (library && Date.now() - refreshed < 15_000) return library;
    scan ??= scanLibrary().then(result => {
        library = result;
        refreshed = Date.now();
        return result;
    }).finally(() => { scan = undefined; });
    return scan;
}
let state;
try {
    state = JSON.parse(await readFile(statePath, 'utf8'));
    if (!Array.isArray(state.favorites) || !Array.isArray(state.playlists)) throw new Error('Invalid state format.');
} catch (error) {
    if (error.code !== 'ENOENT') throw error;
    state = { favorites: [], playlists: [] };
}
let stateQueue = Promise.resolve();
function changeState(change) {
    const next = stateQueue.then(async () => {
        const draft = structuredClone(state);
        const result = change(draft);
        const temporary = statePath + '.' + randomUUID() + '.tmp';
        await writeFile(temporary, JSON.stringify(draft), { mode: 0o600 });
        await rename(temporary, statePath);
        state = draft;
        return result;
    });
    stateQueue = next.catch(() => {});
    return next;
}

function reply(res, body = {}) {
    res.writeHead(200, { 'Content-Type': 'application/json; charset=utf-8', 'Cache-Control': 'no-store' });
    res.end(JSON.stringify({ shenye: { status: 'ok', ...body } }));
}
function fail(res, code, message, status = 200) {
    res.writeHead(status, { 'Content-Type': 'application/json; charset=utf-8', 'Cache-Control': 'no-store' });
    res.end(JSON.stringify({ shenye: { status: 'failed', error: { code, message } } }));
}
function allowed(query, action) {
    const salt = query.get('s') || '';
    const proof = query.get('t') || '';
    if (query.get('u') !== username || !/^[a-z0-9-]{8,40}$/i.test(salt) || !/^[a-f0-9]{64}$/.test(proof)) return false;
    const expected = createHmac('sha256', token).update([username, action, salt].join('\n')).digest();
    return timingSafeEqual(expected, Buffer.from(proof, 'hex'));
}
const validLimit = (value, fallback) => value !== null && /^\d+$/.test(value) ? Math.min(Number(value), 2000) : fallback;

async function serveFile(req, res, file, type) {
    const actual = file && await lookup(file);
    if (!actual) return fail(res, 70, '文件不存在', 404);
    const size = (await stat(actual)).size;
    let start = 0;
    let end = size - 1;
    let status = 200;
    if (req.headers.range) {
        const match = req.headers.range.match(/^bytes=(\d*)-(\d*)$/);
        if (match && (match[1] || match[2])) {
            start = match[1] ? Number(match[1]) : Math.max(0, size - Number(match[2]));
            end = match[1] && match[2] ? Math.min(end, Number(match[2])) : end;
        } else {
            start = size;
        }
        if (start > end || start >= size) {
            res.writeHead(416, { 'Content-Range': 'bytes */' + size });
            return res.end();
        }
        status = 206;
    }
    const headers = { 'Content-Type': type, 'Content-Length': Math.max(0, end - start + 1),
        'Accept-Ranges': 'bytes', 'Cache-Control': 'private, no-store' };
    if (status === 206) headers['Content-Range'] = 'bytes ' + start + '-' + end + '/' + size;
    res.writeHead(status, headers);
    if (req.method === 'HEAD' || !size) return res.end();
    const stream = createReadStream(actual, { start, end });
    stream.on('error', () => res.destroy());
    res.on('close', () => stream.destroy());
    stream.pipe(res);
}

const server = createServer(async (req, res) => {
    try {
        const address = new URL(req.url, 'http://localhost');
        const match = address.pathname.match(/^\/shenye\/v1\/([a-zA-Z0-9]+)$/);
        if (!match) return fail(res, 70, '未知接口', 404);
        const [, action] = match;
        if (!allowed(address.searchParams, action)) return fail(res, 40, '用户名或访问令牌错误');
        if (mutations.has(action) ? req.method !== 'POST' : !['GET', 'HEAD'].includes(req.method)) {
            return fail(res, 10, '请求方法不支持', 405);
        }
        if (action === 'ping') return reply(res, { version: 1 });
        const catalog = await getLibrary();
        const params = address.searchParams;
        const id = params.get('id');
        const result = catalog.songs.get(id);
        const query = (params.get('query') || '').trim().toLowerCase();
        const found = text => text.toLowerCase().includes(query);
        const songOf = track => {
            const song = catalog.songs.get(track)?.song;
            return song && { ...song, starred: state.favorites.includes(track) ? 'yes' : undefined };
        };
        const albumOf = ({ cover, songIds, modified: timestamp, ...album }) => ({ ...album, songCount: songIds.length });
        const artistOf = artist => ({ id: artist.id, name: artist.name, albumCount: artist.albumIds.size });
        const playlistOf = playlist => ({ id: playlist.id, name: playlist.name, owner: username, songCount: playlist.songIds.length });
        const allSongs = () => [...catalog.songs.keys()].map(songOf);
        const allAlbums = () => [...catalog.albums.values()];
        const allArtists = () => [...catalog.artists.values()];
        const slice = (values, key, defaultCount) => values.slice(0, validLimit(params.get(key), defaultCount));

        switch (action) {
            case 'getIndexes': return reply(res, { indexes: { lastModified: catalog.modified } });
            case 'getScanStatus': return reply(res, { scanStatus: { scanning: false, count: catalog.songs.size, lastScan: catalog.marker } });
            case 'getAlbumList2': {
                const offsetParam = params.get('offset');
                const offset = offsetParam && /^\d+$/.test(offsetParam) ? Number(offsetParam) : 0;
                const albums = allAlbums().sort(params.get('type') === 'alphabeticalByArtist'
                    ? (a, b) => sortNames(a.artist, b.artist) || sortNames(a.name, b.name) || a.id.localeCompare(b.id)
                    : (a, b) => b.modified - a.modified || a.id.localeCompare(b.id));
                return reply(res, { albumList2: { album: slice(albums.slice(offset), 'size', 50).map(albumOf) } });
            }
            case 'getAlbum': {
                const album = catalog.albums.get(id);
                return album ? reply(res, { album: { ...albumOf(album), song: album.songIds.map(songOf) } }) : fail(res, 70, '专辑不存在');
            }
            case 'getArtists': return reply(res, { artists: { index: [{ name: '#', artist: allArtists().map(artistOf) }] } });
            case 'getArtist': {
                const artist = catalog.artists.get(id);
                return artist ? reply(res, { artist: { ...artistOf(artist), album: [...artist.albumIds].map(key => albumOf(catalog.albums.get(key))) } }) : fail(res, 70, '艺术家不存在');
            }
            case 'search3': return reply(res, { searchResult3: {
                song: slice(allSongs().filter(song => found([song.title, song.artist, song.album].join(' '))), 'songCount', 30),
                album: slice(allAlbums().filter(album => found([album.name, album.artist].join(' '))).map(albumOf), 'albumCount', 30),
                artist: slice(allArtists().filter(artist => found(artist.name)).map(artistOf), 'artistCount', 30)
            } });
            case 'getRandomSongs': return reply(res, { randomSongs: { song: slice(allSongs().sort(() => Math.random() - 0.5), 'size', 50) } });
            case 'getSimilarSongs2': return reply(res, { similarSongs2: { song: slice(allSongs().filter(song => song.id !== id && song.artistId === result?.song.artistId), 'count', 20) } });
            case 'getArtistInfo2': return reply(res, { artistInfo2: { similarArtist: [] } });
            case 'getStarred2': return reply(res, { starred2: { song: state.favorites.map(songOf).filter(Boolean) } });
            case 'star':
            case 'unstar':
                if (!result) return fail(res, 70, '歌曲不存在');
                await changeState(draft => { draft.favorites = draft.favorites.filter(key => key !== id); if (action === 'star') draft.favorites.push(id); });
                return reply(res);
            case 'getPlaylists': return reply(res, { playlists: { playlist: state.playlists.map(playlistOf) } });
            case 'getPlaylist': {
                const playlist = state.playlists.find(item => item.id === id);
                return playlist ? reply(res, { playlist: { ...playlistOf(playlist), entry: playlist.songIds.map(songOf).filter(Boolean) } }) : fail(res, 70, '歌单不存在');
            }
            case 'createPlaylist': {
                const name = (params.get('name') || '').trim();
                if (!name) return fail(res, 10, '请输入歌单名称');
                const playlist = await changeState(draft => {
                    const created = { id: randomUUID(), name, songIds: params.getAll('songId').filter(key => catalog.songs.has(key)) };
                    draft.playlists.push(created);
                    return created;
                });
                return reply(res, { playlist: playlistOf(playlist) });
            }
            case 'updatePlaylist':
            case 'deletePlaylist': {
                const key = params.get('playlistId') || id;
                const changed = await changeState(draft => {
                    const index = draft.playlists.findIndex(item => item.id === key);
                    if (index < 0) return false;
                    if (action === 'deletePlaylist') {
                        draft.playlists.splice(index, 1);
                    } else {
                        const playlist = draft.playlists[index];
                        const name = params.get('name')?.trim();
                        const removed = new Set(params.getAll('songIndexToRemove').map(Number));
                        if (name) playlist.name = name;
                        playlist.songIds = playlist.songIds.filter((_, position) => !removed.has(position));
                        playlist.songIds.push(...params.getAll('songIdToAdd').filter(songId => catalog.songs.has(songId)));
                    }
                    return true;
                });
                return changed ? reply(res) : fail(res, 70, '歌单不存在');
            }
            case 'getLyricsBySongId': return reply(res, { lyrics: { value: await readLyrics(result) } });
            case 'reportPlayback':
            case 'scrobble': return reply(res);
            case 'stream':
            case 'download': return await serveFile(req, res, result?.file, result?.song.contentType);
            case 'getCoverArt': {
                const cover = catalog.albums.get(id)?.cover;
                return await serveFile(req, res, cover, cover && formats[extname(cover).slice(1).toLowerCase()]);
            }
            default: return fail(res, 70, '未知操作', 404);
        }
    } catch (error) {
        console.error(error.message);
        if (res.headersSent) res.destroy();
        else fail(res, 0, '服务器读取失败', 500);
    }
});

await getLibrary();
server.listen(port, host, () => console.log('声页音乐服务器 ' + host + ':' + port + ' · ' + library.songs.size + ' 首歌曲'));
