import { Capacitor, registerPlugin } from '@capacitor/core'

export interface AiriOverlayStatus {
  /** The "display over other apps" permission is granted. */
  overlayGranted: boolean
  /** The floating bubble service is running. */
  running: boolean
}

interface AiriOverlayPlugin {
  getStatus: () => Promise<AiriOverlayStatus>
  requestOverlayPermission: () => Promise<AiriOverlayStatus>
  /** Asks for screen-capture consent, then starts the bubble. Rejects with `capture-denied` or `overlay-permission-missing`. */
  start: () => Promise<AiriOverlayStatus>
  stop: () => Promise<AiriOverlayStatus>
}

/** Native floating AIRI bubble (Android only). */
export const AiriOverlay = registerPlugin<AiriOverlayPlugin>('AiriOverlay')

export function isOverlaySupported(): boolean {
  return Capacitor.isNativePlatform() && Capacitor.getPlatform() === 'android'
}

/** Methods that the overlay's native WebView host exposes to the overlay page. */
export interface AiriOverlayHost {
  ready: () => void
  close: () => void
}

/** What the native host calls on the overlay page. */
export interface AiriOverlayPageApi {
  open: (token: number) => void
  requestClose: () => void
}

declare global {
  interface Window {
    AiriOverlayHost?: AiriOverlayHost
    __airiOverlay?: AiriOverlayPageApi
  }
}
