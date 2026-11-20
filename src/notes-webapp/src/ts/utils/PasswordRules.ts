/** As the server's ChangePasswordApi.MIN_LENGTH, which checks it again. */
export const MIN_PASSWORD_LENGTH = 12;

/** Characters as the server counts them: code points, so an emoji is one, not two. */
function length(text: string): number {
  return [...text].length;
}

/** @return what's wrong with a new password typed twice, undefined if nothing. */
export function newPasswordProblem(password: string, again: string): string | undefined {
  if (length(password) < MIN_PASSWORD_LENGTH) return "The new password must be at least " + MIN_PASSWORD_LENGTH + " characters long.";
  if (password !== again) return "The two new passwords differ.";
  return undefined;
}
