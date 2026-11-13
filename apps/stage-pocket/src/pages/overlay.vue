<script setup lang="ts">
import type { OverlayPoint } from '../composables/overlay-gesture'

import { WidgetStage } from '@proj-airi/stage-ui/components/scenes'
import { useChatStore } from '@proj-airi/stage-ui/stores/chat'
import { useChatSessionStore } from '@proj-airi/stage-ui/stores/chat/session-store'
import { useChatStreamStore } from '@proj-airi/stage-ui/stores/chat/stream-store'
import { useVoiceCallStore } from '@proj-airi/stage-ui/stores/voice-call'
import { storeToRefs } from 'pinia'
import { computed, onBeforeUnmount, onMounted, ref, shallowRef, watch } from 'vue'

import { captureCircle, captureWholeScreen, circledBounds } from '../composables/overlay-gesture'

/**
 * Full-screen AIRI overlay. It only runs inside the native overlay WebView (see AiriOverlayService.kt).
 * The native side freezes the screen into a screenshot, calls `open()`, and this page shows the screenshot,
 * AIRI sliding in from the side, a Gemini-style bottom bar and the circle-to-ask gesture.
 */
const chat = useChatStore()
const chatSession = useChatSessionStore()
const chatStream = useChatStreamStore()
const voiceCall = useVoiceCallStore()
const { activeSessionId, messages } = storeToRefs(chatSession)
const { streamingMessage } = storeToRefs(chatStream)
const { activeTurns } = storeToRefs(chat)

type Phase = 'idle' | 'drawing' | 'thinking' | 'answer'

const side = ref<'left' | 'right'>((localStorage.getItem('airi-overlay-side') as 'left' | 'right' | null) ?? 'right')
const visible = ref(false)
const phase = ref<Phase>('idle')
const screen = ref({ width: window.innerWidth, height: window.innerHeight })
const shotUrl = shallowRef<string>()
const cropPreview = ref<string>()
const draft = ref('')
const errorMessage = ref('')

const points = ref<OverlayPoint[]>([])
const finger = ref<OverlayPoint>({ x: 0, y: 0 })
const hand = ref<OverlayPoint>({ x: 0, y: 0 })
const handShown = ref(false)
const canvasRef = ref<HTMLCanvasElement>()
const cardRef = ref<HTMLElement>()
const cardHeight = ref(0)
let baselineCount = 0
let raf = 0

// region answer text

function textOf(content: unknown): string {
  if (typeof content === 'string')
    return content
  if (Array.isArray(content))
    return content.map(part => (part && typeof part === 'object' && 'text' in part ? String((part as { text: unknown }).text) : '')).join('')
  return ''
}

const isSending = computed(() => activeTurns.value.some(turn => turn.sessionId === activeSessionId.value))
const answerText = computed(() => {
  const streaming = textOf(streamingMessage.value?.content).trim()
  if (streaming)
    return streaming
  const fresh = messages.value.slice(baselineCount).filter(message => message.role === 'assistant')
  return textOf(fresh.at(-1)?.content).trim()
})

watch([isSending, answerText], ([sending, text]) => {
  if (phase.value !== 'thinking' && phase.value !== 'answer')
    return
  if (text)
    phase.value = 'answer'
  if (!sending && text)
    handShown.value = false
})

// endregion

// region hand and arm animation

const anchor = computed(() => ({
  x: side.value === 'right' ? screen.value.width * 0.9 : screen.value.width * 0.1,
  y: screen.value.height * 0.52 - cardHeight.value,
}))

function tick() {
  const target = phase.value === 'drawing' ? finger.value : anchor.value
  hand.value = {
    x: hand.value.x + (target.x - hand.value.x) * 0.22,
    y: hand.value.y + (target.y - hand.value.y) * 0.22,
  }
  raf = requestAnimationFrame(tick)
}

const armPath = computed(() => {
  const from = anchor.value
  const to = hand.value
  const midX = (from.x + to.x) / 2
  const midY = Math.max(from.y, to.y) + 40
  return `M ${from.x} ${from.y} Q ${midX} ${midY} ${to.x} ${to.y}`
})

// endregion

// region gesture

function drawStroke() {
  const canvas = canvasRef.value
  const context = canvas?.getContext('2d')
  if (!canvas || !context)
    return
  context.clearRect(0, 0, canvas.width, canvas.height)
  if (points.value.length < 2)
    return
  const ratio = canvas.width / screen.value.width
  context.save()
  context.scale(ratio, ratio)
  context.lineCap = 'round'
  context.lineJoin = 'round'
  const gradient = context.createLinearGradient(0, 0, screen.value.width, screen.value.height)
  gradient.addColorStop(0, '#6ea8ff')
  gradient.addColorStop(0.5, '#c28bff')
  gradient.addColorStop(1, '#ff7aa8')
  context.shadowColor = 'rgba(194, 139, 255, 0.9)'
  context.shadowBlur = 18
  context.strokeStyle = gradient
  context.lineWidth = 5
  context.beginPath()
  points.value.forEach((point, index) => index === 0 ? context.moveTo(point.x, point.y) : context.lineTo(point.x, point.y))
  context.stroke()
  context.restore()
}

function isUi(target: EventTarget | null) {
  return target instanceof Element && !!target.closest('[data-ui]')
}

function onPointerDown(event: PointerEvent) {
  if (!visible.value || isUi(event.target) || phase.value === 'thinking')
    return
  ;(event.currentTarget as HTMLElement).setPointerCapture(event.pointerId)
  points.value = [{ x: event.clientX, y: event.clientY }]
  finger.value = { x: event.clientX, y: event.clientY }
  if (!handShown.value)
    hand.value = { ...anchor.value }
  handShown.value = true
  phase.value = 'drawing'
  cropPreview.value = undefined
  drawStroke()
}

function onPointerMove(event: PointerEvent) {
  if (phase.value !== 'drawing')
    return
  const point = { x: event.clientX, y: event.clientY }
  points.value.push(point)
  finger.value = point
  drawStroke()
}

async function onPointerUp() {
  if (phase.value !== 'drawing')
    return
  const bounds = circledBounds(points.value, screen.value)
  if (!bounds || !shotUrl.value) {
    points.value = []
    handShown.value = false
    phase.value = answerText.value ? 'answer' : 'idle'
    drawStroke()
    return
  }
  try {
    const capture = await captureCircle(shotUrl.value, screen.value, points.value, bounds)
    cropPreview.value = capture.cropPreviewUrl
    await ask(draft.value.trim() || 'What is this? Explain what I circled.', [capture.annotated, capture.crop], 'The first image is my screen with a pink outline drawn around what I circled. The second image is the circled region cropped.')
  }
  catch (error) {
    fail(error)
  }
}

// endregion

// region asking

async function ask(text: string, images: string[], context: string) {
  const sessionId = activeSessionId.value
  if (!sessionId) {
    errorMessage.value = 'AIRI is still starting. Try again in a moment.'
    phase.value = 'idle'
    return
  }
  errorMessage.value = ''
  baselineCount = messages.value.length
  phase.value = 'thinking'
  draft.value = ''
  try {
    await chat.send({
      sessionId,
      text: `${text}\n\n(${context})`,
      attachments: images.map(data => ({ type: 'image' as const, data, mimeType: 'image/jpeg' })),
    })
  }
  catch (error) {
    fail(error)
  }
}

async function submitDraft() {
  const text = draft.value.trim()
  if (!text || !shotUrl.value || phase.value === 'thinking')
    return
  try {
    const whole = await captureWholeScreen(shotUrl.value)
    points.value = []
    drawStroke()
    cropPreview.value = undefined
    await ask(text, [whole], 'The image is my current screen.')
  }
  catch (error) {
    fail(error)
  }
}

function fail(error: unknown) {
  console.error('[overlay] ask failed', error)
  errorMessage.value = error instanceof Error ? error.message : 'Something went wrong.'
  handShown.value = false
  phase.value = 'idle'
}

async function toggleVoice() {
  if (voiceCall.active) {
    voiceCall.hangUp()
    return
  }
  const failure = await voiceCall.start()
  if (failure)
    errorMessage.value = `Voice is unavailable (${failure}). Check Hearing settings and the microphone.`
}

// endregion

// region open and close

let objectUrl: string | undefined

async function open(token: number) {
  screen.value = { width: window.innerWidth, height: window.innerHeight }
  try {
    const response = await fetch(`/__overlay/capture.jpg?t=${token}`, { cache: 'no-store' })
    const blob = await response.blob()
    if (objectUrl)
      URL.revokeObjectURL(objectUrl)
    objectUrl = URL.createObjectURL(blob)
    shotUrl.value = objectUrl
  }
  catch (error) {
    console.error('[overlay] screenshot failed', error)
  }
  points.value = []
  cropPreview.value = undefined
  errorMessage.value = ''
  draft.value = ''
  handShown.value = false
  baselineCount = messages.value.length
  phase.value = 'idle'
  // Size the canvas for the pixel ratio so the glow stays sharp.
  const canvas = canvasRef.value
  if (canvas) {
    const ratio = window.devicePixelRatio || 1
    canvas.width = Math.round(screen.value.width * ratio)
    canvas.height = Math.round(screen.value.height * ratio)
  }
  hand.value = { ...anchor.value }
  requestAnimationFrame(() => visible.value = true)
}

function requestClose() {
  if (voiceCall.active)
    voiceCall.hangUp()
  visible.value = false
  handShown.value = false
  // Wait for the slide-out before the native side hides the window.
  window.setTimeout(() => window.AiriOverlayHost?.close(), 380)
}

onMounted(() => {
  window.__airiOverlay = { open, requestClose }
  raf = requestAnimationFrame(tick)
  window.AiriOverlayHost?.ready()
  if (cardRef.value)
    new ResizeObserver(() => cardHeight.value = phase.value === 'idle' ? 0 : (cardRef.value?.offsetHeight ?? 0)).observe(cardRef.value)
})

watch(phase, (value) => {
  if (value === 'idle')
    cardHeight.value = 0
  else if (cardRef.value)
    cardHeight.value = cardRef.value.offsetHeight
})

onBeforeUnmount(() => {
  cancelAnimationFrame(raf)
  if (objectUrl)
    URL.revokeObjectURL(objectUrl)
  delete window.__airiOverlay
})

// endregion
</script>

<template>
  <div
    class="overlay airi-native-transparent-surface"
    :class="{ visible }"
    @pointerdown="onPointerDown"
    @pointermove="onPointerMove"
    @pointerup="onPointerUp"
    @pointercancel="onPointerUp"
  >
    <!-- Frozen screen. Sized from the open-time viewport so the keyboard never stretches it. -->
    <img
      v-if="shotUrl"
      class="shot"
      :src="shotUrl"
      :style="{ width: `${screen.width}px`, height: `${screen.height}px` }"
      alt=""
      draggable="false"
    >
    <div class="dim" />

    <canvas ref="canvasRef" class="stroke" :style="{ width: `${screen.width}px`, height: `${screen.height}px` }" />

    <!-- AIRI on the side, looking at the finger. -->
    <div
      class="model"
      :class="[side]"
      :style="{ transform: visible ? `translate(0, ${-cardHeight}px)` : `translate(${side === 'right' ? '105%' : '-105%'}, ${-cardHeight}px)` }"
    >
      <WidgetStage :paused="!visible" :cursor-position="phase === 'drawing' ? finger : anchor" :enable-orbit-controls="false" />
    </div>

    <!-- Arm and fingertip that follow the user's finger. -->
    <svg class="arm" :class="{ shown: handShown && visible }" :viewBox="`0 0 ${screen.width} ${screen.height}`">
      <path :d="armPath" class="arm-outline" />
      <path :d="armPath" class="arm-fill" />
      <circle :cx="hand.x" :cy="hand.y" r="15" class="tip" />
      <circle :cx="hand.x" :cy="hand.y" r="15" class="pulse" />
    </svg>

    <!-- Answer card -->
    <div v-show="phase === 'thinking' || phase === 'answer' || errorMessage" ref="cardRef" data-ui class="card">
      <div class="card-head">
        <img v-if="cropPreview" :src="cropPreview" class="thumb" alt="">
        <span class="card-title">{{ phase === 'thinking' && !answerText ? 'Looking…' : 'AIRI' }}</span>
      </div>
      <div v-if="phase === 'thinking' && !answerText" class="shimmer"><span /><span /><span /></div>
      <p v-if="answerText" class="answer">
        {{ answerText }}
      </p>
      <p v-if="errorMessage" class="error">
        {{ errorMessage }}
      </p>
    </div>

    <!-- Bottom bar: text, voice, close -->
    <div data-ui class="bar">
      <input
        v-model="draft"
        class="field"
        type="text"
        enterkeyhint="send"
        placeholder="Ask about screen, or circle something"
        @keydown.enter.prevent="submitDraft"
      >
      <button class="round" :class="{ live: voiceCall.active }" aria-label="Voice" @click="toggleVoice">
        <svg viewBox="0 0 24 24"><path d="M12 14a3 3 0 0 0 3-3V6a3 3 0 0 0-6 0v5a3 3 0 0 0 3 3Zm5-3a5 5 0 0 1-10 0H5a7 7 0 0 0 6 6.92V21h2v-3.08A7 7 0 0 0 19 11h-2Z" /></svg>
      </button>
      <button v-if="draft.trim()" class="round send" aria-label="Send" @click="submitDraft">
        <svg viewBox="0 0 24 24"><path d="M12 4l7 7-1.4 1.4L13 7.8V20h-2V7.8l-4.6 4.6L5 11l7-7Z" /></svg>
      </button>
      <button v-else class="round" aria-label="Close" @click="requestClose">
        <svg viewBox="0 0 24 24"><path d="M6.4 5l5.6 5.6L17.6 5 19 6.4 13.4 12l5.6 5.6-1.4 1.4-5.6-5.6L6.4 19 5 17.6 10.6 12 5 6.4 6.4 5Z" /></svg>
      </button>
    </div>
  </div>
</template>

<style scoped>
.overlay {
  position: fixed;
  inset: 0;
  overflow: hidden;
  touch-action: none;
  user-select: none;
  -webkit-user-select: none;
  background: transparent;
  opacity: 0;
  transition: opacity 0.25s ease;
  font-family: system-ui, -apple-system, 'Segoe UI', Roboto, sans-serif;
}
.overlay.visible { opacity: 1; }
.shot { position: absolute; top: 0; left: 0; object-fit: fill; pointer-events: none; }
.dim { position: absolute; inset: 0; background: rgba(8, 10, 20, 0.28); pointer-events: none; }
.stroke { position: absolute; top: 0; left: 0; pointer-events: none; }

.model {
  position: absolute;
  bottom: 84px;
  width: 58vw;
  height: 58vh;
  pointer-events: none;
  transition: transform 0.55s cubic-bezier(0.2, 1, 0.3, 1);
}
.model.right { right: -4vw; }
.model.left { left: -4vw; }

.arm { position: absolute; inset: 0; width: 100%; height: 100%; pointer-events: none; opacity: 0; transition: opacity 0.25s ease; }
.arm.shown { opacity: 1; }
.arm path { fill: none; stroke-linecap: round; }
.arm-outline { stroke: rgba(40, 30, 80, 0.45); stroke-width: 18; }
.arm-fill { stroke: #fff4ee; stroke-width: 12; }
.tip { fill: #fff; stroke: #c28bff; stroke-width: 3; filter: drop-shadow(0 0 8px rgba(194, 139, 255, 0.9)); }
.pulse { fill: none; stroke: rgba(194, 139, 255, 0.8); stroke-width: 2; transform-box: fill-box; transform-origin: center; animation: pulse 1.2s ease-out infinite; }
@keyframes pulse { from { transform: scale(1); opacity: 0.9; } to { transform: scale(2.6); opacity: 0; } }

.card {
  position: absolute;
  left: 12px;
  right: 12px;
  bottom: calc(84px + env(safe-area-inset-bottom, 0px));
  max-height: 42vh;
  overflow-y: auto;
  padding: 14px 16px;
  border-radius: 22px;
  color: #f4f2ff;
  background: rgba(24, 22, 40, 0.82);
  border: 1px solid rgba(255, 255, 255, 0.12);
  backdrop-filter: blur(18px);
  -webkit-backdrop-filter: blur(18px);
  box-shadow: 0 10px 40px rgba(0, 0, 0, 0.45);
  animation: rise 0.35s cubic-bezier(0.2, 1, 0.3, 1);
}
@keyframes rise { from { transform: translateY(24px); opacity: 0; } to { transform: none; opacity: 1; } }
.card-head { display: flex; align-items: center; gap: 10px; margin-bottom: 8px; }
.card-title { font-size: 12px; letter-spacing: 0.08em; text-transform: uppercase; opacity: 0.7; }
.thumb { width: 44px; height: 44px; border-radius: 12px; object-fit: cover; border: 1px solid rgba(255, 255, 255, 0.2); }
.answer { margin: 0; font-size: 15px; line-height: 1.5; white-space: pre-wrap; user-select: text; -webkit-user-select: text; }
.error { margin: 0; font-size: 13px; color: #ff9db8; }
.shimmer { display: grid; gap: 8px; }
.shimmer span { height: 10px; border-radius: 6px; background: linear-gradient(90deg, rgba(255,255,255,0.08), rgba(255,255,255,0.28), rgba(255,255,255,0.08)); background-size: 200% 100%; animation: shimmer 1.2s linear infinite; }
.shimmer span:nth-child(2) { width: 80%; }
.shimmer span:nth-child(3) { width: 55%; }
@keyframes shimmer { from { background-position: 200% 0; } to { background-position: -200% 0; } }

.bar {
  position: absolute;
  left: 12px;
  right: 12px;
  bottom: calc(14px + env(safe-area-inset-bottom, 0px));
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 8px 8px 8px 18px;
  border-radius: 999px;
  background: rgba(10, 10, 14, 0.92);
  border: 1px solid rgba(255, 255, 255, 0.1);
  box-shadow: 0 6px 28px rgba(0, 0, 0, 0.5);
}
.field { flex: 1; min-width: 0; border: 0; outline: 0; background: transparent; color: #fff; font-size: 16px; }
.field::placeholder { color: rgba(255, 255, 255, 0.55); }
.round { flex: none; width: 42px; height: 42px; display: grid; place-items: center; border: 0; border-radius: 50%; background: rgba(255, 255, 255, 0.1); }
.round svg { width: 22px; height: 22px; fill: #fff; }
.round.live { background: #ff3b6b; animation: pulse-bg 1.4s ease-in-out infinite; }
.round.send { background: #c28bff; }
@keyframes pulse-bg { 50% { box-shadow: 0 0 0 8px rgba(255, 59, 107, 0.25); } }
</style>

<route lang="yaml">
name: OverlayPage
meta:
  layout: plain
</route>
