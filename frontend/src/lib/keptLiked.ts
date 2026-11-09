// Your liked songs as last read, kept in this browser (IndexedDB: they can be thousands, too
// much for localStorage) so searching your library needs no YouTube requests most of the time.
// Storage can be unavailable (private mode): then nothing is kept and search reads them again.
import type { Song } from './protocol.gen';

export interface KeptLiked {
	/** When the newest ones were last looked at. */
	at: number;
	/** When the whole list was last read (0: never to the end). */
	fullAt: number;
	songs: Song[];
}

const STORE = 'liked';

function open(): Promise<IDBDatabase> {
	return new Promise((resolve, reject) => {
		const request = indexedDB.open('ytmp-liked', 1);
		request.onupgradeneeded = () => request.result.createObjectStore(STORE);
		request.onsuccess = () => resolve(request.result);
		request.onerror = () => reject(request.error);
	});
}

async function withStore<T>(mode: IDBTransactionMode, work: (store: IDBObjectStore) => IDBRequest<T>): Promise<T | undefined> {
	try {
		const db = await open();
		try {
			return await new Promise<T>((resolve, reject) => {
				const request = work(db.transaction(STORE, mode).objectStore(STORE));
				request.onsuccess = () => resolve(request.result);
				request.onerror = () => reject(request.error);
			});
		} finally {
			db.close();
		}
	} catch {
		return undefined;
	}
}

export const readLiked = async (key: string): Promise<KeptLiked | null> => ((await withStore('readonly', (store) => store.get(key))) as KeptLiked | undefined) ?? null;

export async function writeLiked(key: string, kept: KeptLiked): Promise<void> {
	await withStore('readwrite', (store) => store.put(kept, key));
}

/** Signing out: nothing of it stays on this device. */
export async function forgetLiked(): Promise<void> {
	await withStore('readwrite', (store) => store.clear());
}
