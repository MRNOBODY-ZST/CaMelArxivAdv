<script setup lang="ts">
import { computed } from 'vue'
import { useRoute } from 'vue-router'

import AppShell from '@/layouts/AppShell.vue'

const route = useRoute()
const publicLayout = computed(() => route.meta.publicLayout === true)
</script>

<template>
  <RouterView v-if="publicLayout" />
  <AppShell v-else>
    <RouterView />
  </AppShell>
</template>
