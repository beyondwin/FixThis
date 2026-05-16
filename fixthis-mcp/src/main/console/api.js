// @requires state.js
            class ConsoleRequestError extends Error {
              constructor({ status, error, message, action, details, bodyText }) {
                super(message || error || bodyText || ('HTTP ' + status));
                this.name = 'ConsoleRequestError';
                this.status = status;
                this.error = error || null;
                this.action = action || null;
                this.details = details || {};
                this.bodyText = bodyText || '';
              }
            }

            function responseContentType(response) {
              if (!response?.headers) return '';
              if (typeof response.headers.get === 'function') return response.headers.get('content-type') || '';
              if (typeof response.headers.get === 'undefined' && typeof response.headers[Symbol.iterator] === 'function') {
                for (const [key, value] of response.headers) {
                  if (String(key).toLowerCase() === 'content-type') return String(value);
                }
              }
              return '';
            }

            async function readStructuredError(response) {
              const bodyText = await response.text();
              const contentType = responseContentType(response);
              if (contentType.includes('application/json') || bodyText.trim().startsWith('{')) {
                try {
                  const parsed = JSON.parse(bodyText);
                  return new ConsoleRequestError({
                    status: response.status,
                    error: parsed.error,
                    message: parsed.message,
                    action: parsed.action,
                    details: parsed.details,
                    bodyText,
                  });
                } catch (_) {
                  return new ConsoleRequestError({ status: response.status, bodyText });
                }
              }
              return new ConsoleRequestError({ status: response.status, bodyText });
            }

            async function requestJson(path, options = {}) {
              const method = (options.method || 'GET').toUpperCase();
              const headers = new Headers(options.headers || {});
              if (['POST', 'PUT', 'PATCH', 'DELETE'].includes(method)) {
                const token = window.FixThisConsoleConfig?.consoleToken;
                if (token) headers.set('X-FixThis-Console-Token', token);
              }
              const response = await fetch(path, { ...options, headers });
              if (!response.ok) {
                throw await readStructuredError(response);
              }
              return response.json();
            }

            async function copyTextToClipboard(text) {
              try {
                if (navigator.clipboard?.writeText) {
                  await navigator.clipboard.writeText(text);
                  return;
                }
              } catch (cause) {
                // Fall back below for browser surfaces that deny Clipboard API writes.
              }
              const fallback = document.createElement('textarea');
              fallback.value = text;
              fallback.setAttribute('readonly', '');
              fallback.style.position = 'fixed';
              fallback.style.top = '-9999px';
              fallback.style.left = '-9999px';
              document.body.appendChild(fallback);
              fallback.focus();
              fallback.select();
              const copied = document.execCommand('copy');
              fallback.remove();
              if (!copied) throw new Error('Copy failed. Select the prompt and copy it manually.');
            }
