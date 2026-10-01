# Authentication

> Status: **decided: servers can trust other servers** (signed-token federation below).
> Details to be worked out.

Feature description: [features/accounts.md](../features/accounts.md#auth-servers).

## Baseline

- Every YTMP server can be an **auth server** with its own accounts.
- A host (server room or phone) is configured with an auth server.
- A user can join with their account if the host uses the **same auth server**, or one
  that **trusts** the user's auth server.
- The host and the auth server should verify each other, for example with a
  **pre-shared key** set up when the host is linked to the auth server.

## Chosen: signed tokens (federation)

Instead of the host asking the auth server about every user:

1. Accounts are identified as `user@auth-server` (like e-mail, Matrix or Mastodon).
2. The user logs in at **their own** auth server and gets a **signed token** (for
   example a JWT) scoped to the room they want to join.
3. The host verifies the signature with the auth server's **public key**, fetched
   once and cached. It doesn't need a shared secret or a live connection per join.
4. Each host keeps a list of **trusted auth servers** (by default just its own),
   so accounts from other servers can join if the host allows them.

Benefits: works across multiple servers, phone hosts only need the public key (they
can even verify offline once it is cached), and the user's password never reaches
the host.

The pre-shared key is then only needed for privileged host ↔ auth server actions
(for example storing role templates or transfer data), not for verifying users.
