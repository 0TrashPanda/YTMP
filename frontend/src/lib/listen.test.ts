import { afterEach, describe, expect, it, vi } from 'vitest';
import { ApiRequestError } from './api';
import { ATTEMPTS, RATE, downsample, listen, wav } from './listen';
import type { RecognizeResponse, Song } from './protocol.gen';

describe('the sound sent', () => {
	it('48 kHz becomes 16 kHz: every 3 samples one, their average', () => {
		const out = downsample(new Float32Array([0, 0.3, 0.6, 1, 1, 1]), 48000, 16000);
		expect(Array.from(out).map((v) => +v.toFixed(2))).toEqual([0.3, 1]);
	});

	it('is a 16-bit mono WAV', () => {
		const bytes = wav(new Float32Array([0, 1, -1]), RATE);
		const view = new DataView(bytes.buffer);
		expect(new TextDecoder().decode(bytes.slice(0, 4))).toBe('RIFF');
		expect(new TextDecoder().decode(bytes.slice(8, 12))).toBe('WAVE');
		expect(view.getUint16(22, true)).toBe(1);
		expect(view.getUint32(24, true)).toBe(16000);
		expect(view.getUint32(40, true)).toBe(6);
		expect([view.getInt16(44, true), view.getInt16(46, true), view.getInt16(48, true)]).toEqual([0, 32767, -32768]);
	});
});

// A microphone at 48 kHz that hears a second of sound every second of (fake) time.
let stopped = 0;
function fakeMicrophone() {
	stopped = 0;
	const processor: { onaudioprocess: ((e: unknown) => void) | null; connect: () => void; disconnect: () => void } = { onaudioprocess: null, connect: () => {}, disconnect: () => {} };
	const timer = setInterval(() => processor.onaudioprocess?.({ inputBuffer: { getChannelData: () => new Float32Array(48000) } }), 1000);
	class FakeContext {
		sampleRate = 48000;
		destination = {};
		createMediaStreamSource = () => ({ connect: () => {}, disconnect: () => {} });
		createScriptProcessor = () => processor;
		close = async () => clearInterval(timer);
	}
	vi.stubGlobal('AudioContext', FakeContext);
	vi.stubGlobal('navigator', { mediaDevices: { getUserMedia: async () => ({ getTracks: () => [{ stop: () => stopped++ }] }) } });
}

const song = { id: 'ytm:x', title: 'Digital World', artists: [], album: null, durationMs: 0, thumbnails: [] } as unknown as Song;

describe('listening', () => {
	afterEach(() => {
		vi.useRealTimers();
		vi.unstubAllGlobals();
	});

	async function run(answers: (RecognizeResponse | Error)[]) {
		vi.useFakeTimers();
		fakeMicrophone();
		const sent: number[] = [];
		const heard = listen(new AbortController().signal, async (bytes) => {
			sent.push((bytes.length - 44) / 2 / RATE);
			const next = answers.shift() ?? { song: null, title: null, artist: null };
			if (next instanceof Error) throw next;
			return next;
		});
		await vi.advanceTimersByTimeAsync(20_000);
		return { heard: await heard, sent };
	}

	it('asks after 3 seconds and stops at the first song', async () => {
		const { heard, sent } = await run([{ song, title: 'Digital World', artist: 'Amaranthe' }]);
		expect(heard).toEqual({ kind: 'song', answer: { song, title: 'Digital World', artist: 'Amaranthe' } });
		expect(sent).toEqual([3]);
		expect(stopped).toBe(1);
	});

	it('asks again with more sound until it knows, and Shazam being busy is no reason to stop', async () => {
		const busy = new ApiRequestError('busy', 429);
		const { heard, sent } = await run([{ song: null, title: null, artist: null }, busy, { song, title: 'x', artist: 'y' }]);
		expect(heard.kind).toBe('song');
		expect(sent).toEqual([3, 5, 8]);
	});

	it('gives up after the last try', async () => {
		const { heard, sent } = await run([]);
		expect(heard).toEqual({ kind: 'nothing' });
		expect(sent).toEqual(ATTEMPTS);
		expect(stopped).toBe(1);
	});

	it('a song that is not on YouTube Music is an answer too (the app searches for it)', async () => {
		const { heard, sent } = await run([{ song: null, title: 'Rare', artist: 'Someone' }]);
		expect(heard).toEqual({ kind: 'song', answer: { song: null, title: 'Rare', artist: 'Someone' } });
		expect(sent).toEqual([3]);
	});

	it('another error stops it, with why', async () => {
		const { heard } = await run([new ApiRequestError('Recognizing songs needs a newer YouTube Music module', 501)]);
		expect(heard).toEqual({ kind: 'error', message: 'Recognizing songs needs a newer YouTube Music module' });
	});
});
