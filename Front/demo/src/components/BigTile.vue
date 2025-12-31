<template>
  <button
    class="big-tile"
    :style="{ backgroundColor: bgColor }"
    @click="$emit('click')"
  >
    <div class="icon" aria-hidden="true">{{ icon }}</div>
    <div class="label">{{ label }}</div>
  </button>
</template>

<script>
export default {
  name: 'BigTile',
  props: {
    label: { type: String, required: true },
    icon: { type: String, default: '' },
    bgColor: { type: String, default: '#2b8a3e' }
  }
}
</script>

<style scoped>
.big-tile {
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  border: none;
  border-radius: 24px;
  padding: 24px;
  width: 100%;
  height: 140px;
  color: #fff;
  font-size: 32px;
  font-weight: 700;
  box-shadow: 0 8px 20px rgba(0,0,0,0.15);
  transition: transform 0.06s ease;
}
.big-tile:active {
  transform: scale(0.98);
}
.icon {
  font-size: 56px;
  line-height: 1;
  margin-bottom: 8px;
}
.label {
  font-size: 32px;
  line-height: 1.2;
}
</style>
