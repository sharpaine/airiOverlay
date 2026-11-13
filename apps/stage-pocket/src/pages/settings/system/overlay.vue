<script setup lang="ts">
import { onMounted, ref } from 'vue'

import { AiriOverlay, isOverlaySupported } from '../../../modules/airi-overlay'

const supported = isOverlaySupported()
const overlayGranted = ref(false)
const running = ref(false)
const busy = ref(false)
const message = ref('')

async function refresh() {
  if (!supported)
    return
  const status = await AiriOverlay.getStatus()
  overlayGranted.value = status.overlayGranted
  running.value = status.running
}

async function enable() {
  busy.value = true
  message.value = ''
  try {
    if (!overlayGranted.value) {
      const status = await AiriOverlay.requestOverlayPermission()
      overlayGranted.value = status.overlayGranted
      if (!status.overlayGranted) {
        message.value = 'Allow "Display over other apps" for AIRI, then try again.'
        return
      }
    }
    // Android asks for screen-capture consent here. Pick "Entire screen" if it offers a choice.
    running.value = (await AiriOverlay.start()).running
  }
  catch (error) {
    message.value = (error as { message?: string })?.message === 'capture-denied'
      ? 'Screen capture was not allowed, so the bubble cannot read your screen.'
      : `Could not start: ${(error as { message?: string })?.message ?? error}`
  }
  finally {
    busy.value = false
    await refresh()
  }
}

async function disable() {
  busy.value = true
  try {
    running.value = (await AiriOverlay.stop()).running
  }
  finally {
    busy.value = false
  }
}

onMounted(refresh)
</script>

<template>
  <div :class="['flex flex-col gap-4 pb-12']">
    <p :class="['text-sm text-neutral-600', 'md:text-base dark:text-neutral-300']">
      Shows a small bubble over every app. Tap it and AIRI slides in from the side. Ask a question, or draw a circle around anything on your screen and AIRI will point at it and explain.
    </p>

    <p v-if="!supported" :class="['text-sm text-amber-600']">
      This only works in the Android app.
    </p>

    <template v-else>
      <button
        :class="['rounded-xl px-4 py-3 font-medium', running ? 'bg-neutral-200 dark:bg-neutral-800' : 'bg-primary-500 text-white']"
        :disabled="busy"
        @click="running ? disable() : enable()"
      >
        {{ running ? 'Turn off floating AIRI' : 'Turn on floating AIRI' }}
      </button>
      <p :class="['text-xs text-neutral-500']">
        Needs "Display over other apps" and screen-capture permission. The screenshot is taken only when you tap the bubble and is sent to your chosen model provider with your question.
      </p>
      <p v-if="message" :class="['text-sm text-red-500']">
        {{ message }}
      </p>
    </template>
  </div>
</template>

<route lang="yaml">
meta:
  layout: settings
  title: Floating AIRI
  subtitleKey: settings.title
  stageTransition:
    name: slide
</route>
