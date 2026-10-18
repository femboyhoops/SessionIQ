// Local tests only: creates a stand-in for Mojang's certificate key so test/smoke.mjs can issue
// player certificates. The private half goes to test/.dev-mojang-key.pem and the public half to
// DEV_CERT_KEY in .dev.vars (both are git-ignored). The server only trusts it for localhost
// requests with ALLOW_DEV_AUTH=1, so it has no effect on a deployed Worker.
//   npm run setup-test-key
import { generateKeyPairSync } from "node:crypto";
import { existsSync, readFileSync, writeFileSync } from "node:fs";

const pem = new URL("../test/.dev-mojang-key.pem", import.meta.url);
const vars = new URL("../.dev.vars", import.meta.url);
const example = new URL("../.dev.vars.example", import.meta.url);

const { publicKey, privateKey } = generateKeyPairSync("rsa", { modulusLength: 2048 });
writeFileSync(pem, privateKey.export({ type: "pkcs8", format: "pem" }));
const line = `DEV_CERT_KEY=${publicKey.export({ type: "spki", format: "der" }).toString("base64")}`;

let content = existsSync(vars) ? readFileSync(vars, "utf8") : readFileSync(example, "utf8");
content = /^DEV_CERT_KEY=.*$/m.test(content) ? content.replace(/^DEV_CERT_KEY=.*$/m, line) : `${content.trimEnd()}\n${line}\n`;
writeFileSync(vars, content);
console.log("Wrote test/.dev-mojang-key.pem and DEV_CERT_KEY in .dev.vars. Restart `npm run dev` to pick it up.");
