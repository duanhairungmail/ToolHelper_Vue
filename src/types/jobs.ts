export type JobStatus = 'idle' | 'loading' | 'success' | 'error' | 'cancelled'

export interface JobState<TResult = unknown> {
  status: JobStatus
  result?: TResult
  error?: string
}
