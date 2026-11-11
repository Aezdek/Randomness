export interface ApiErrorBody {
  error: string;
  message: string;
  /** For the few errors fixed by running something on the server: what to run there. */
  command?: string | null;
}

export class ApiError extends Error {
  constructor(
    readonly status: number,
    readonly code: string,
    message: string,
    readonly command: string | null = null,
  ) {
    super(message);
  }
}

export function messageOf(error: unknown): string {
  if (error instanceof ApiError) return error.message;
  if (error instanceof Error) return error.message;
  return 'Something went wrong';
}
