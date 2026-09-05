import { onBeforeUnmount, ref } from 'vue'
import type { JobState } from '@/types/jobs'
import type { ToolService } from '@/api/contracts'

export function useMockTask<TRequest, TResult>(service: ToolService<TRequest, TResult>) {
  const state = ref<JobState<TResult>>({ status: 'idle' })
  let controller: AbortController | undefined

  async function execute(request: TRequest) {
    controller?.abort()
    controller = new AbortController()
    state.value = { status: 'loading' }
    try {
      const result = await service.execute(request, controller.signal)
      state.value = { status: 'success', result }
      return result
    } catch (error) {
      if (error instanceof DOMException && error.name === 'AbortError') {
        state.value = { status: 'cancelled' }
        return
      }
      const message = error instanceof Error ? error.message : '操作失败'
      state.value = { status: 'error', error: message }
    }
  }

  function cancel() {
    controller?.abort()
  }

  onBeforeUnmount(cancel)
  return { state, execute, cancel }
}
