import { execFileSync } from "node:child_process";
import fs from "node:fs";
import path from "node:path";
import GithubSlugger from "github-slugger";
import { marked } from "marked";

// Release notes and old plans are immutable records. Their navigation READMEs
// remain maintained and are checked, as are new, not-yet-staged documents.
export function collectDocumentationFiles(root) {
  return [...new Set(execFileSync("git", [
    "ls-files", "--cached", "--others", "--exclude-standard", "-z",
  ], { cwd: root, encoding: "utf8" }).split("\0"))]
    .filter((file) => file.endsWith(".md") && fs.existsSync(path.join(root, file)))
    .filter((file) => file !== "CHANGELOG.md")
    .filter((file) => !/^docs\/(?:specs|plans)\/(?!README\.md$)/.test(file))
    .filter((file) => !/^docs\/releases\/v\d.*\.md$/.test(file))
    .filter((file) => !file.startsWith("docs/superpowers/"))
    .sort();
}

// Marked owns Markdown syntax (including reference links and code blocks).
// Keep only filesystem resolution and GitHub heading IDs in this checker.
function decodeEntities(text) {
  const named = { amp: "&", lt: "<", gt: ">", quot: '"', apos: "'", nbsp: " " };
  return text.replace(/&(#x[\da-f]+|#\d+|amp|lt|gt|quot|apos|nbsp);/gi, (entity, value) => {
    if (!value.startsWith("#")) return named[value.toLowerCase()];
    const codePoint = value[1].toLowerCase() === "x"
      ? Number.parseInt(value.slice(2), 16) : Number.parseInt(value.slice(1), 10);
    return codePoint > 0 && codePoint <= 0x10ffff ? String.fromCodePoint(codePoint) : "\uFFFD";
  });
}

function inlineText(tokens) {
  return tokens.map((token) => {
    if (token.type === "html") return "";
    if (token.tokens) return inlineText(token.tokens);
    return token.text ?? "";
  }).join("");
}

function parseDocument(content) {
  // Marked normalizes newlines before creating raw tokens.
  const source = content.replace(/\r\n?/g, "\n");
  const links = [];
  const anchors = new Set();
  const slugger = new GithubSlugger();
  let blockCursor = 0;
  for (const block of marked.lexer(source)) {
    const foundBlock = source.indexOf(block.raw, blockCursor);
    const blockStart = foundBlock < 0 ? blockCursor : foundBlock;
    blockCursor = blockStart + block.raw.length;
    let tokenCursor = 0;
    marked.walkTokens([block], (token) => {
      if (token.type === "heading") {
        anchors.add(slugger.slug(decodeEntities(inlineText(token.tokens)).trim()));
      }
      if (token.type === "html" && !token.raw.startsWith("<!--")) {
        for (const match of token.raw.matchAll(/<(?:a|h[1-6])\b[^>]*\b(?:id|name)=["']([^"']+)["']/gi)) {
          anchors.add(decodeEntities(match[1]));
        }
      }
      if (!["link", "image", "code", "codespan", "html"].includes(token.type)) return;
      let offset = block.raw.indexOf(token.raw, tokenCursor);
      // Nested lists/quotes strip indentation from their raw child tokens.
      if (offset < 0) offset = block.raw.indexOf(token.raw.split("\n")[0], tokenCursor);
      if (offset < 0) offset = 0;
      tokenCursor = offset + (["code", "codespan", "html"].includes(token.type) ? token.raw.length : 1);
      if (token.type === "link" || token.type === "image") {
        links.push({
          destination: decodeEntities(token.href),
          line: source.slice(0, blockStart + offset).split("\n").length,
        });
      }
    });
  }
  return { links, anchors };
}

export function validateDocLinks(root, files = collectDocumentationFiles(root)) {
  const errors = [];
  const anchorCache = new Map();
  let checked = 0;
  for (const file of files) {
    const source = path.join(root, file);
    const content = fs.readFileSync(source, "utf8");
    for (const link of parseDocument(content).links) {
      if (/^(?:[a-z][a-z\d+.-]*:|\/\/)/i.test(link.destination)) continue;
      checked += 1;
      const label = `${file}:${link.line}: ${link.destination}`;
      let targetPath;
      let anchor;
      try {
        const [pathname, ...fragment] = link.destination.split("#");
        targetPath = decodeURIComponent(pathname.split("?")[0]);
        anchor = decodeURIComponent(fragment.join("#"));
      } catch {
        errors.push(`${label}: invalid URL encoding`);
        continue;
      }
      let target = targetPath ? path.resolve(path.dirname(source), targetPath) : source;
      if (!fs.existsSync(target)) {
        errors.push(`${label}: target is missing`);
        continue;
      }
      if (fs.statSync(target).isDirectory()) {
        if (!anchor) continue;
        target = path.join(target, "README.md");
        if (!fs.existsSync(target)) {
          errors.push(`${label}: directory has no README for heading lookup`);
          continue;
        }
      }
      if (anchor && target.endsWith(".md")) {
        if (!anchorCache.has(target)) {
          anchorCache.set(target, parseDocument(fs.readFileSync(target, "utf8")).anchors);
        }
        if (!anchorCache.get(target).has(anchor)) errors.push(`${label}: heading or explicit anchor is missing`);
      }
    }
  }
  return { errors, checked, files: files.length };
}
