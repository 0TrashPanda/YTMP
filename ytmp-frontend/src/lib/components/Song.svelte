<script lang="ts">
  export let title: string;
  export let artist: string;
</script>

<div class="song p-2 border-b border-gray-300 flex justify-between items-center">
  <div>
    <div class="font-semibold">{title}</div>
    <div class="text-sm text-gray-500">{artist}</div>
  </div>
  <button class="play-btn px-2 py-1 bg-blue-500 text-white rounded">Play</button>
</div>

<style>
  .song:hover {
    background-color: #f9f9f9;
  }
</style>
