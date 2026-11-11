// Friends, groups of friends and the songs they share with you (docs/features/friends.md). Kept
// on your account's server, reached like your listening history (listening.svelte.ts), so it
// works in rooms everywhere you're logged in with your YTMP account.
import { listening } from './listening.svelte';
import type { FriendGroup, FriendRequest, FriendsOverview, GroupRequest, ReceivedShare, ReceivedShares, ShareRequest, ShareResult, Song } from './protocol.gen';

class Friends {
	/** Your friends, requests and groups; null until loaded. */
	overview = $state<FriendsOverview | null>(null);
	/** What friends shared with you, newest first; null until loaded (or without an account). */
	shares = $state<ReceivedShare[] | null>(null);

	get available(): boolean {
		return listening.available;
	}

	async load(): Promise<FriendsOverview> {
		const overview = await listening.call<FriendsOverview>('/api/account/friends');
		this.overview = overview;
		return overview;
	}

	/** The songs shared with you (none without an account, or when your server can't be reached). */
	async loadShares(): Promise<ReceivedShare[]> {
		await listening.load();
		if (!listening.available) return (this.shares = []);
		try {
			this.shares = (await listening.call<ReceivedShares>('/api/account/shares')).shares;
		} catch {
			this.shares ??= [];
		}
		return this.shares;
	}

	/** Ask [username] to be friends, or say yes to their request. */
	async add(username: string): Promise<FriendsOverview> {
		this.overview = await listening.call<FriendsOverview>('/api/account/friends', 'POST', { username } satisfies FriendRequest);
		return this.overview;
	}

	/** No longer friends, or a request declined or taken back. */
	async remove(username: string): Promise<void> {
		this.overview = await listening.call<FriendsOverview>(`/api/account/friends/${encodeURIComponent(username)}`, 'DELETE');
	}

	async createGroup(name: string, members: string[]): Promise<FriendGroup> {
		const group = await listening.call<FriendGroup>('/api/account/groups', 'POST', { name, members } satisfies GroupRequest);
		await this.load();
		return group;
	}

	async updateGroup(id: string, name: string, members: string[]): Promise<void> {
		await listening.call<FriendGroup>(`/api/account/groups/${encodeURIComponent(id)}`, 'PUT', { name, members } satisfies GroupRequest);
		await this.load();
	}

	/** Leaves a group, or deletes it when it's yours. */
	async leaveGroup(id: string): Promise<void> {
		await listening.call<null>(`/api/account/groups/${encodeURIComponent(id)}`, 'DELETE');
		await this.load();
	}

	share(song: Song, friends: string[], groups: string[], note: string): Promise<ShareResult> {
		return listening.call<ShareResult>('/api/account/shares', 'POST', { song, friends, groups, note } satisfies ShareRequest);
	}

	/** A song shared with you off Home, or one you shared taken back from everyone. */
	async dismiss(id: string): Promise<void> {
		this.shares = this.shares?.filter((s) => s.id !== id) ?? null;
		await listening.call<null>(`/api/account/shares/${encodeURIComponent(id)}`, 'DELETE');
	}
}

export const friends = new Friends();

/** Who shared it, and with which group: "Bob · Hardcore". */
export function sharedBy(share: ReceivedShare): string {
	return share.group ? `${share.from.displayName} · ${share.group}` : share.from.displayName;
}
