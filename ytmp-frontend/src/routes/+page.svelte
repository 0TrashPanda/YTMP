<script lang="ts">
  import SongItem from "$lib/components/SongItem.svelte";

  let query = "";
  let results: any[] = [];

  async function doSearch() {
    if (!query.trim()) {
      results = [];
      return;
    }
    const res = await fetch(`/api/search?q=${encodeURIComponent(query)}`);
    results = await res.json();
  }

  function playNow(songId: string) {
    fetch(`/api/playNow?id=${songId}`, { method: "POST" });
  }

  function playNext(songId: string) {
    fetch(`/api/playNext?id=${songId}`, { method: "POST" });
  }

  function addToQueue(songId: string) {
    fetch(`/api/addToQueue?id=${songId}`, { method: "POST" });
  }
</script>

<div class="flex h-screen bg-gray-900 text-gray-100">
  <!-- SEARCH RESULTS -->
  <div class="w-1/3 border-r border-gray-700 flex flex-col">
    <div class="sticky top-0 z-10 p-2 bg-gray-900 border-b border-gray-700">
      <input
        type="text"
        placeholder="Search..."
        bind:value={query}
        on:input={doSearch}
        class="w-full p-2 rounded bg-gray-800 border border-gray-700 focus:outline-none focus:ring focus:ring-gray-600"
      />
    </div>

    <div class="flex-1 overflow-y-auto">
      {#each results as item, index (item.videoId ?? index)}
        {#if item.resultType === "song"}
          <SongItem
            song={item}
            {playNow}
            {playNext}
            {addToQueue}
          />
        {:else}
          <div class="p-2 border-b border-gray-700">
            {item.title}
          </div>
        {/if}
      {/each}
    </div>
  </div>

  <!-- NOW PLAYING -->
  <div class="w-1/3 border-r border-gray-700 p-2">
    <h2 class="text-lg font-semibold mb-2">Now Playing</h2>
    <div class="flex-1 overflow-y-auto">
      <!-- Will be populated later via socket -->
    </div>
  </div>

  <!-- QUEUE -->
  <div class="w-1/3 p-2">
    <h2 class="text-lg font-semibold mb-2">Queue</h2>
    <div class="flex-1 overflow-y-auto">
      <!-- Will be populated later via socket -->
    </div>
  </div>
</div>
