<script lang="ts">
  import { onDestroy } from "svelte";
  import Song from "$lib/components/Song.svelte";

  let query = "";
  let results: { title: string; artist: string }[] = [];
  let timeout: NodeJS.Timeout;

  // Trigger search 300ms after user stops typing
  $: if (query.trim() !== "") {
    clearTimeout(timeout);
    timeout = setTimeout(fetchResults, 300);
  } else {
    results = [];
  }

  async function fetchResults() {
    try {
      const res = await fetch(`/api/search?query=${encodeURIComponent(query)}`);
      if (res.ok) {
        results = await res.json();
      } else {
        results = [];
      }
    } catch (err) {
      console.error("Search error:", err);
      results = [];
    }
  }

  onDestroy(() => clearTimeout(timeout));
</script>

<div class="search-container p-4">
  <input
    type="text"
    placeholder="Search for songs..."
    bind:value={query}
    class="border p-2 rounded w-full"
  />

  {#if results.length > 0}
    <div class="results mt-4 space-y-2">
      {#each results as song}
        <Song title={song.title} artist={song.artist} />
      {/each}
    </div>
  {:else if query.trim() !== ""}
    <p class="mt-4 text-gray-500">No results found</p>
  {/if}
</div>
