// Recognize a song (docs/features/ui.md): the microphone records, and after 2.5, 4, 6 and 10
// seconds this device makes Shazam's signature of what it has (shazamSignature.ts) and the host
// asks Shazam (POST /api/recognize). The first answer ends it; the page then shows it and finds
// the song on YouTube Music (GET /api/recognize/song). Measured with real lookups: 2 seconds was
// never enough, 2.5 was for every song Shazam knows; Shazam answers in 0.5–1.5 s.
import { ApiRequestError, recognize } from './api';
import type { RecognizeResponse } from './protocol.gen';
import { SIGNATURE_RATE, signature, type Signature } from './shazamSignature';

/** When to ask, in seconds of sound. Each try is one lookup, and Shazam limits how often. */
export const ATTEMPTS = [2.5, 4, 6, 10];

/** [samples] at [from] Hz, as [to] Hz: each new sample is the average of the old ones it covers (a simple low-pass too). */
export function downsample(samples: Float32Array, from: number, to: number): Float32Array {
	if (from === to) return samples;
	const ratio = from / to;
	const out = new Float32Array(Math.floor(samples.length / ratio));
	for (let i = 0; i < out.length; i++) {
		const start = Math.floor(i * ratio);
		const end = Math.min(samples.length, Math.floor((i + 1) * ratio));
		let sum = 0;
		for (let j = start; j < end; j++) sum += samples[j];
		out[i] = end > start ? sum / (end - start) : 0;
	}
	return out;
}

/** -1…1 as 16-bit samples, the way shazamio turns its own into them. */
export function toInt16(samples: Float32Array): Int16Array {
	return Int16Array.from(samples, (s) => Math.trunc(Math.max(-1, Math.min(1, s)) * 32767));
}

/** Whether this browser can record (a page on http:// other than localhost can't). */
export const canListen = () => typeof navigator !== 'undefined' && !!navigator.mediaDevices?.getUserMedia && typeof AudioContext !== 'undefined';

export type Heard =
	| { kind: 'found'; answer: RecognizeResponse }
	| { kind: 'nothing' }
	| { kind: 'error'; message: string };

/**
 * Listens until Shazam knows the song, or [ATTEMPTS] run out. [ask] sends the signature (the
 * host, in tests a fake). Stops when [signal] aborts (Cancel); the microphone is off again when it returns.
 */
export async function listen(signal: AbortSignal, ask: (signature: Signature) => Promise<RecognizeResponse> = recognize): Promise<Heard> {
	let stream: MediaStream;
	try {
		stream = await navigator.mediaDevices.getUserMedia({ audio: { echoCancellation: false, noiseSuppression: false, autoGainControl: false, channelCount: 1 } });
	} catch {
		return { kind: 'error', message: 'YTMP may not use the microphone. Allow it in the browser (or Android) settings.' };
	}
	const context = new AudioContext();
	const chunks: Float32Array[] = [];
	let recorded = 0;
	const source = context.createMediaStreamSource(stream);
	// ScriptProcessor: old but everywhere (Chrome 90 too), and enough for a few seconds of sound.
	const processor = context.createScriptProcessor(2048, 1, 1);
	processor.onaudioprocess = (e) => {
		const samples = new Float32Array(e.inputBuffer.getChannelData(0));
		chunks.push(samples);
		recorded += samples.length;
	};
	source.connect(processor);
	processor.connect(context.destination);
	const stop = () => {
		processor.disconnect();
		source.disconnect();
		stream.getTracks().forEach((t) => t.stop());
		context.close().catch(() => {});
	};
	const seconds = () => recorded / context.sampleRate;
	const wait = (s: number) =>
		new Promise<void>((resolve) => {
			const check = () => (signal.aborted || seconds() >= s ? resolve() : setTimeout(check, 50));
			check();
		});
	try {
		for (const at of ATTEMPTS) {
			await wait(at);
			if (signal.aborted) return { kind: 'nothing' };
			const all = new Float32Array(recorded);
			let offset = 0;
			for (const c of chunks) all.set(c, (offset += c.length) - c.length);
			try {
				const answer = await ask(signature(toInt16(downsample(all, context.sampleRate, SIGNATURE_RATE))));
				if (answer.title) return { kind: 'found', answer };
			} catch (e) {
				// Shazam busy for a moment: the next try has more sound anyway.
				if (!(e instanceof ApiRequestError && e.status === 429)) return { kind: 'error', message: e instanceof Error ? e.message : 'Recognizing failed' };
			}
		}
		return { kind: 'nothing' };
	} finally {
		stop();
	}
}
