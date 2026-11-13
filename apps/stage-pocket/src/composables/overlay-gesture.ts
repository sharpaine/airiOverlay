export interface OverlayPoint {
  x: number
  y: number
}

export interface OverlayBounds {
  x: number
  y: number
  width: number
  height: number
}

/** A path shorter than this is a tap, not a circle. */
const MIN_PATH_LENGTH = 48
const MIN_SIZE = 28

export function pathLength(points: OverlayPoint[]): number {
  let total = 0
  for (let i = 1; i < points.length; i++)
    total += Math.hypot(points[i].x - points[i - 1].x, points[i].y - points[i - 1].y)
  return total
}

/** Bounding box of the stroke, grown by `padding` and kept inside the screen. Undefined for taps and scribbles. */
export function circledBounds(points: OverlayPoint[], screen: { width: number, height: number }, padding = 0.08): OverlayBounds | undefined {
  if (points.length < 3 || pathLength(points) < MIN_PATH_LENGTH)
    return undefined

  const xs = points.map(p => p.x)
  const ys = points.map(p => p.y)
  const minX = Math.min(...xs)
  const maxX = Math.max(...xs)
  const minY = Math.min(...ys)
  const maxY = Math.max(...ys)
  if (maxX - minX < MIN_SIZE && maxY - minY < MIN_SIZE)
    return undefined

  const padX = Math.max((maxX - minX) * padding, 12)
  const padY = Math.max((maxY - minY) * padding, 12)
  const x = Math.max(0, minX - padX)
  const y = Math.max(0, minY - padY)
  return {
    x,
    y,
    width: Math.min(screen.width, maxX + padX) - x,
    height: Math.min(screen.height, maxY + padY) - y,
  }
}

function loadImage(url: string): Promise<HTMLImageElement> {
  return new Promise((resolve, reject) => {
    const image = new Image()
    image.onload = () => resolve(image)
    image.onerror = () => reject(new Error('Failed to load the screenshot'))
    image.src = url
  })
}

function toBase64Jpeg(canvas: HTMLCanvasElement, quality: number): string {
  return canvas.toDataURL('image/jpeg', quality).split(',')[1] ?? ''
}

export interface CircleCapture {
  /** The whole screen with the user's outline drawn on it, scaled down. */
  annotated: string
  /** Just the circled region at full resolution. */
  crop: string
  /** A data URL of the crop for the answer card thumbnail. */
  cropPreviewUrl: string
}

/**
 * Builds the two images sent to the model. Coordinates are CSS pixels of the screen-sized screenshot view.
 */
export async function captureCircle(screenshotUrl: string, screen: { width: number, height: number }, points: OverlayPoint[], bounds: OverlayBounds): Promise<CircleCapture> {
  const image = await loadImage(screenshotUrl)
  const scaleX = image.naturalWidth / screen.width
  const scaleY = image.naturalHeight / screen.height

  const crop = document.createElement('canvas')
  crop.width = Math.max(1, Math.round(bounds.width * scaleX))
  crop.height = Math.max(1, Math.round(bounds.height * scaleY))
  crop.getContext('2d')!.drawImage(
    image,
    bounds.x * scaleX,
    bounds.y * scaleY,
    bounds.width * scaleX,
    bounds.height * scaleY,
    0,
    0,
    crop.width,
    crop.height,
  )

  const maxSide = 1280
  const shrink = Math.min(1, maxSide / Math.max(image.naturalWidth, image.naturalHeight))
  const annotated = document.createElement('canvas')
  annotated.width = Math.round(image.naturalWidth * shrink)
  annotated.height = Math.round(image.naturalHeight * shrink)
  const context = annotated.getContext('2d')!
  context.drawImage(image, 0, 0, annotated.width, annotated.height)
  context.strokeStyle = '#ff3b6b'
  context.lineWidth = Math.max(4, annotated.width * 0.006)
  context.lineJoin = 'round'
  context.lineCap = 'round'
  context.beginPath()
  points.forEach((point, index) => {
    const x = point.x / screen.width * annotated.width
    const y = point.y / screen.height * annotated.height
    if (index === 0)
      context.moveTo(x, y)
    else
      context.lineTo(x, y)
  })
  context.stroke()

  return {
    annotated: toBase64Jpeg(annotated, 0.85),
    crop: toBase64Jpeg(crop, 0.9),
    cropPreviewUrl: crop.toDataURL('image/jpeg', 0.7),
  }
}

/** Returns the screenshot as a base64 JPEG for a question about the whole screen. */
export async function captureWholeScreen(screenshotUrl: string): Promise<string> {
  const image = await loadImage(screenshotUrl)
  const shrink = Math.min(1, 1280 / Math.max(image.naturalWidth, image.naturalHeight))
  const canvas = document.createElement('canvas')
  canvas.width = Math.round(image.naturalWidth * shrink)
  canvas.height = Math.round(image.naturalHeight * shrink)
  canvas.getContext('2d')!.drawImage(image, 0, 0, canvas.width, canvas.height)
  return toBase64Jpeg(canvas, 0.85)
}
