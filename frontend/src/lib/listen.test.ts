import { afterEach, describe, expect, it, vi } from 'vitest';
import { ApiRequestError } from './api';
import { ATTEMPTS, downsample, listen, toInt16 } from './listen';
import type { RecognizeResponse } from './protocol.gen';

describe('the sound sent', () => {
	it('48 kHz becomes 16 kHz: every 3 samples one, their average', () => {
		const out = downsample(new Float32Array([0, 0.3, 0.6, 1, 1, 1]), 48000, 16000);
		expect(Array.from(out).map((v) => +v.toFixed(2))).toEqual([0.3, 1]);
	});

	it('turns -1…1 into 16-bit samples like shazamio', () => {
		expect(Array.from(toInt16(new Float32Array([0, 1, -1, 0.5, 2])))).toEqual([0, 32767, -32767, 16383, 32767]);
	});
});

// A microphone at 48 kHz that hears half a second of sound every half second of (fake) time.
let stopped = 0;
function fakeMicrophone() {
	stopped = 0;
	const processor: { onaudioprocess: ((e: unknown) => void) | null; connect: () => void; disconnect: () => void } = { onaudioprocess: null, connect: () => {}, disconnect: () => {} };
	const timer = setInterval(() => processor.onaudioprocess?.({ inputBuffer: { getChannelData: () => new Float32Array(24000) } }), 500);
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

const found: RecognizeResponse = { title: 'Digital World', artist: 'Amaranthe', album: 'Massive Addictive', art: null, isrc: null };
const nothing: RecognizeResponse = { title: null, artist: null, album: null, art: null, isrc: null };

describe('listening', () => {
	afterEach(() => {
		vi.useRealTimers();
		vi.unstubAllGlobals();
	});

	async function run(answers: (RecognizeResponse | Error)[]) {
		vi.useFakeTimers();
		fakeMicrophone();
		const sent: number[] = [];
		const heard = listen(new AbortController().signal, async (signature) => {
			expect(signature.uri).toMatch(/^data:audio\/vnd\.shazam\.sig;base64,/);
			sent.push(signature.sampleMs / 1000);
			const next = answers.shift() ?? nothing;
			if (next instanceof Error) throw next;
			return next;
		});
		await vi.advanceTimersByTimeAsync(20_000);
		return { heard: await heard, sent };
	}

	it('asks after 2.5 seconds and stops at the first answer', async () => {
		const { heard, sent } = await run([found]);
		expect(heard).toEqual({ kind: 'found', answer: found });
		expect(sent).toEqual([2.5]);
		expect(stopped).toBe(1);
	});

	it('asks again with more sound until it knows, and Shazam being busy is no reason to stop', async () => {
		const busy = new ApiRequestError('busy', 429);
		const { heard, sent } = await run([nothing, busy, found]);
		expect(heard.kind).toBe('found');
		expect(sent).toEqual([2.5, 4, 6]);
	});

	it('gives up after the last try', async () => {
		const { heard, sent } = await run([]);
		expect(heard).toEqual({ kind: 'nothing' });
		expect(sent).toEqual(ATTEMPTS);
		expect(stopped).toBe(1);
	});

	it('another error stops it, with why', async () => {
		const { heard } = await run([new ApiRequestError('Recognizing songs needs a newer YouTube Music module', 501)]);
		expect(heard).toEqual({ kind: 'error', message: 'Recognizing songs needs a newer YouTube Music module' });
	});
});
