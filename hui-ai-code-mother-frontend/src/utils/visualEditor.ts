/** Same-origin iframe element selection for targeted AI edits. */
export interface ElementInfo {
  tagName: string
  id: string
  className: string
  textContent: string
  selector: string
  pagePath: string
  rect: { top: number; left: number; width: number; height: number }
}

export interface VisualEditorOptions {
  onElementSelected?: (elementInfo: ElementInfo) => void
  onError?: (message: string) => void
}

export class VisualEditor {
  private iframe: HTMLIFrameElement | null = null
  private isEditMode = false

  constructor(private options: VisualEditorOptions = {}) {}

  init(iframe: HTMLIFrameElement) {
    if (this.iframe && this.iframe !== iframe) this.disableEditMode()
    this.iframe = iframe
  }

  enableEditMode() {
    try {
      const doc = this.iframe?.contentDocument
      if (!doc?.body || this.iframe?.contentWindow?.location.origin !== window.location.origin) {
        throw new Error('预览需要同源访问')
      }
      if (!doc.getElementById('visual-edit-script')) {
        const script = doc.createElement('script')
        script.id = 'visual-edit-script'
        script.textContent = `(${installVisualEditor.toString()})()`
        doc.head.appendChild(script)
      }
      this.isEditMode = true
      this.send({ type: 'TOGGLE_EDIT_MODE', editMode: true })
    } catch {
      this.isEditMode = false
      this.options.onError?.('无法开启编辑，请等待预览加载完成，并通过同源 /api 代理访问预览。')
    }
    return this.isEditMode
  }

  disableEditMode() {
    this.isEditMode = false
    this.send({ type: 'TOGGLE_EDIT_MODE', editMode: false })
  }

  toggleEditMode() {
    if (this.isEditMode) this.disableEditMode()
    else this.enableEditMode()
    return this.isEditMode
  }

  clearSelection() {
    this.send({ type: 'CLEAR_SELECTION' })
  }

  onIframeLoad() {
    if (this.isEditMode) return this.enableEditMode()
    return false
  }

  handleIframeMessage(event: MessageEvent) {
    if (
      !this.isEditMode ||
      event.source !== this.iframe?.contentWindow ||
      event.origin !== window.location.origin ||
      event.data?.channel !== 'hui-visual-editor'
    )
      return
    const info = event.data?.data?.elementInfo
    if (
      event.data.type === 'ELEMENT_SELECTED' &&
      info &&
      ['tagName', 'id', 'className', 'textContent', 'selector', 'pagePath'].every(
        (key) => typeof info[key] === 'string',
      )
    ) {
      this.options.onElementSelected?.(info as ElementInfo)
    }
  }

  destroy() {
    this.send({ type: 'DESTROY' })
    this.isEditMode = false
    this.iframe = null
  }

  private send(message: Record<string, unknown>) {
    this.iframe?.contentWindow?.postMessage(
      { ...message, channel: 'hui-visual-editor' },
      window.location.origin,
    )
  }
}

// This function is serialized into the preview: keep it self-contained.
function installVisualEditor() {
  let enabled = false
  let hovered: Element | null = null
  let selected: Element | null = null
  const overlays = ['hover', 'selected'].map((kind) => {
    const overlay = document.createElement('div')
    overlay.id = `hui-visual-${kind}`
    overlay.style.cssText = `position:fixed;pointer-events:none;z-index:2147483647;display:none;box-sizing:border-box;border:${kind === 'hover' ? '2px dashed #1890ff' : '3px solid #52c41a'};`
    document.body.appendChild(overlay)
    return overlay
  })

  function redraw() {
    ;[hovered, selected].forEach((element, index) => {
      const overlay = overlays[index]!
      if (!enabled || !element?.isConnected) {
        overlay.style.display = 'none'
        return
      }
      const rect = element.getBoundingClientRect()
      Object.assign(overlay.style, {
        display: 'block',
        top: `${rect.top}px`,
        left: `${rect.left}px`,
        width: `${rect.width}px`,
        height: `${rect.height}px`,
      })
    })
  }

  function targetOf(event: Event) {
    const target = event.target
    return target instanceof Element &&
      !overlays.includes(target as HTMLDivElement) &&
      !['HTML', 'BODY', 'SCRIPT', 'STYLE'].includes(target.tagName.toUpperCase())
      ? target
      : null
  }

  function selectorFor(element: Element) {
    const parts: string[] = []
    let current: Element | null = element
    while (current && current !== document.documentElement) {
      let part = current.tagName.toLowerCase()
      if (current.id && document.querySelectorAll(`#${CSS.escape(current.id)}`).length === 1) {
        parts.unshift(`${part}#${CSS.escape(current.id)}`)
        break
      }
      const classes = Array.from(current.classList).map((name) => CSS.escape(name))
      if (classes.length) part += `.${classes.join('.')}`
      const siblings = Array.from(current.parentElement?.children || [])
      part += `:nth-child(${siblings.indexOf(current) + 1})`
      parts.unshift(part)
      current = current.parentElement
    }
    return parts.join(' > ')
  }

  function onOver(event: Event) {
    if (!enabled) return
    hovered = targetOf(event)
    redraw()
  }

  function onOut() {
    hovered = null
    redraw()
  }

  function blockPointer(event: Event) {
    if (!enabled) return
    event.preventDefault()
    event.stopImmediatePropagation()
  }

  function onClick(event: Event) {
    if (!enabled) return
    blockPointer(event)
    selected = targetOf(event)
    hovered = null
    redraw()
    if (!selected) return
    const rect = selected.getBoundingClientRect()
    const url = new URL(location.href)
    url.searchParams.delete('_preview')
    const pagePath =
      url.pathname.replace(/^\/api\/static\/[^/]+\/(?:dist\/)?/, '') + url.search + url.hash
    window.parent.postMessage(
      {
        channel: 'hui-visual-editor',
        type: 'ELEMENT_SELECTED',
        data: {
          elementInfo: {
            tagName: selected.tagName,
            id: selected.id,
            className: selected.getAttribute('class') || '',
            textContent: (selected.textContent || '').trim().slice(0, 100),
            selector: selectorFor(selected),
            pagePath,
            rect: { top: rect.top, left: rect.left, width: rect.width, height: rect.height },
          },
        },
      },
      location.origin,
    )
  }

  function onMessage(event: MessageEvent) {
    if (
      event.source !== window.parent ||
      event.origin !== location.origin ||
      event.data?.channel !== 'hui-visual-editor'
    )
      return
    if (event.data.type === 'TOGGLE_EDIT_MODE') {
      enabled = event.data.editMode === true
      hovered = null
      selected = null
      redraw()
    } else if (event.data.type === 'CLEAR_SELECTION') {
      selected = null
      redraw()
    } else if (event.data.type === 'DESTROY') {
      document.removeEventListener('mouseover', onOver, true)
      document.removeEventListener('mouseout', onOut, true)
      document.removeEventListener('pointerdown', blockPointer, true)
      document.removeEventListener('click', onClick, true)
      window.removeEventListener('scroll', redraw, true)
      window.removeEventListener('resize', redraw)
      window.removeEventListener('message', onMessage)
      overlays.forEach((overlay) => overlay.remove())
      document.getElementById('visual-edit-script')?.remove()
    }
  }

  document.addEventListener('mouseover', onOver, true)
  document.addEventListener('mouseout', onOut, true)
  document.addEventListener('pointerdown', blockPointer, true)
  document.addEventListener('click', onClick, true)
  window.addEventListener('scroll', redraw, true)
  window.addEventListener('resize', redraw)
  window.addEventListener('message', onMessage)
}
