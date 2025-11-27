<script lang="ts">
  export let song: any;
  export let playNow: (id: string) => void;
  export let playNext: (id: string) => void;
  export let addToQueue: (id: string) => void;

  let showMenu = false;

  function toggleMenu() {
    showMenu = !showMenu;
  }

  function closeMenu() {
    showMenu = false;
  }
</script>

<div class="grid grid-cols-3 items-center p-2 border-b border-gray-700">
  <div>{song.title}</div>
  <div>{song.artist || song.channel || ""}</div>
  <div class="flex justify-end relative">
    <button class="p-1 rounded hover:bg-gray-700" on:click={toggleMenu}>
      &#9776;
    </button>
    {#if showMenu}
      <div class="absolute right-0 mt-1 w-36 bg-gray-800 border border-gray-700 rounded shadow-lg z-10">
        <button
          class="w-full text-left p-2 hover:bg-gray-700"
          on:click={() => { playNow(song.videoId); closeMenu(); }}
        >Play Now</button>
        <button
          class="w-full text-left p-2 hover:bg-gray-700"
          on:click={() => { playNext(song.videoId); closeMenu(); }}
        >Play Next</button>
        <button
          class="w-full text-left p-2 hover:bg-gray-700"
          on:click={() => { addToQueue(song.videoId); closeMenu(); }}
        >Add to Queue</button>
      </div>
    {/if}
  </div>
</div>
