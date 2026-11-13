// Friends, groups of friends and the songs they share with you (docs/features/accounts/friends.md). Kept
// on your account's server, reached like your listening history (listening.svelte.ts), so it
// works in rooms everywhere you're logged in with your YTMP account.
import { listening } from './listening.svelte';
import type { FriendGroup, FriendRequest, FriendsOverview, GroupRequest, ReceivedShare, ReceivedShares, ShareRequest, ShareResult, ShowOnHome, Song } from './protocol.gen';

/** A friend's or a group's page: what they shared with you, and whether that shows on Home. */
export type FriendTarget = { kind: 'friend'; username: string; name: string } | { kind: 'group'; id: string; name: string };

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

	/** Undo of [dismiss]: the song is back on Home, where it was. */
	async restore(share: ReceivedShare): Promise<void> {
		await listening.call<null>(`/api/account/shares/${encodeURIComponent(share.id)}/restore`, 'POST');
		if (this.shares && !this.shares.some((s) => s.id === share.id)) this.shares = [...this.shares, share].sort((a, b) => b.at - a.at);
	}

	/** Everything [target] shared with you, newest first, also what you took off Home. */
	async history(target: FriendTarget): Promise<ReceivedShare[]> {
		const query = target.kind === 'friend' ? `from=${encodeURIComponent(target.username)}` : `group=${encodeURIComponent(target.id)}`;
		return (await listening.call<ReceivedShares>(`/api/account/shares?${query}`)).shares;
	}

	/** Whether what [target] shares with you shows on your Home. */
	onHome(target: FriendTarget): boolean {
		const o = this.overview;
		if (!o) return true;
		return target.kind === 'friend' ? !o.hiddenFriends.includes(target.username) : !o.hiddenGroups.includes(target.id);
	}

	/** Shows what [target] shares on your Home or not (Home's songs are loaded again). */
	async showOnHome(target: FriendTarget, show: boolean): Promise<void> {
		const path = target.kind === 'friend' ? `friends/${encodeURIComponent(target.username)}` : `groups/${encodeURIComponent(target.id)}`;
		this.overview = await listening.call<FriendsOverview>(`/api/account/${path}/home`, 'PUT', { show } satisfies ShowOnHome);
		void this.loadShares();
	}
}

export const friends = new Friends();

/** Who shared it, and with which group: "Bob · Hardcore". */
export function sharedBy(share: ReceivedShare): string {
	return share.group ? `${share.from.displayName} · ${share.group}` : share.from.displayName;
}
