"use strict";
const $ = (id) => document.getElementById(id);
const number = (value) => new Intl.NumberFormat("pt-BR").format(value);
const assets = ["BRL", "BTC", "ETH"];
let refreshing = false;
let lastRestingOrder = "resting-A";
const examples = [
  ["01 · Creditar BRL na conta A", "35=U1|1=A|55=BRL|38=600000|", "fund-A"],
  ["02 · Creditar BTC na conta B", "35=U1|1=B|55=BTC|38=2|", "fund-B"],
  ["03 · Vender 1 BTC a 500.000", "35=D|1=B|55=BTC/BRL|54=2|44=500000|38=1|", "sell"],
  ["04 · Comprar 1 BTC a 500.000", "35=D|1=A|55=BTC/BRL|54=1|44=500000|38=1|", "buy"],
  ["05 · Compra no book a 100", "35=D|1=A|55=BTC/BRL|54=1|44=100|38=1|", "resting-A"],
  ["06 · Cancelar compra a 100", "35=F|1=A|55=BTC/BRL|", "cancel"],
  ["07 · Debitar 50.000 BRL de A", "35=U6|1=A|55=BRL|38=50000|", "debit"]
];
function fix(body, id) { return `8=FIX.4.4|${body}49=gateway|11=${id}|`; }
for (const [label, body, prefix] of examples) {
  const button = document.createElement("button");
  button.type = "button"; button.className = "example";
  const title = document.createElement("strong"); title.textContent = label;
  const code = document.createElement("code"); code.textContent = fix(body + (prefix === "cancel" ? "41=<ID da compra>|" : ""), prefix);
  button.append(title, code);
  button.addEventListener("click", () => {
    $("command").value = fix(body + (prefix === "cancel" ? `41=${lastRestingOrder}|` : ""), `${prefix}-${crypto.randomUUID()}`);
    $("command").focus();
  });
  $("examples").append(button);
}
function cell(row, value, detail) {
  const td = document.createElement("td"); td.textContent = String(value);
  if (detail !== undefined) { const small = document.createElement("small"); small.textContent = String(detail); td.append(small); }
  row.append(td);
}
function empty(target, message, columns) {
  const row = document.createElement("tr"); const td = document.createElement("td");
  td.className = "empty"; td.colSpan = columns; td.textContent = message; row.append(td); target.replaceChildren(row);
}
async function query(path) {
  const response = await fetch(path, {signal: AbortSignal.timeout(5000), cache: "no-store"});
  if (!response.ok) throw new Error(`Consulta indisponível (HTTP ${response.status}). Verifique o engine.`);
  // Preserve long integer values beyond JavaScript's safe integer range.
  const body = await response.text();
  return JSON.parse(body.replace(/("(?:price|remainingQuantity|entrySequence|available|locked|total)"\s*:\s*)(-?\d+)/g, '$1"$2"'));
}
async function balances() {
  const accounts = [...new Set($("accounts").value.split(",").map(v => v.trim()).filter(Boolean))];
  if (!accounts.length) { empty($("balances"), "Informe uma conta para consultar.", 4); $("balance-error").textContent = ""; return true; }
  const results = await Promise.allSettled(accounts.flatMap(account => assets.map(async asset =>
    ({account, asset, balance: await query(`/accounts/${encodeURIComponent(account)}/balances?asset=${asset}`)}))));
  $("balances").replaceChildren(); let failed = 0;
  results.forEach((result, i) => {
    const row = document.createElement("tr"); const account = accounts[Math.floor(i / assets.length)]; const asset = assets[i % assets.length];
    cell(row, account, asset);
    if (result.status === "fulfilled") {
      const b = result.value.balance; [b.available, b.locked, b.total].forEach(v => cell(row, number(BigInt(v))));
    } else { failed++; ["—", "—", "—"].forEach(v => cell(row, v)); }
    $("balances").append(row);
  });
  $("balance-error").textContent = failed ? "Não foi possível consultar todos os saldos. Verifique o engine e as contas informadas." : "";
  return failed === 0;
}
function side(target, levels) {
  target.replaceChildren();
  for (const level of levels) for (const order of level.orders) {
    const row = document.createElement("tr"); cell(row, number(BigInt(level.price))); cell(row, number(BigInt(order.remainingQuantity))); cell(row, order.accountId, order.clientOrderId); target.append(row);
  }
  if (!target.children.length) empty(target, "Nenhuma ordem aberta", 3);
}
async function book() {
  try {
    const view = await query(`/books?instrument=${encodeURIComponent($("instrument").value)}`);
    side($("bids"), view.bids); side($("asks"), view.asks); $("book-error").textContent = ""; return true;
  } catch (error) {
    $("book-error").textContent = error.message; empty($("bids"), "Consulta indisponível", 3); empty($("asks"), "Consulta indisponível", 3); return false;
  }
}
async function refresh() {
  if (refreshing) return;
  refreshing = true; $("refresh").disabled = true;
  try {
    const results = await Promise.all([balances(), book()]);
    $("updated").textContent = `${results.every(Boolean) ? "Atualizado" : "Consulta incompleta"} às ${new Date().toLocaleTimeString("pt-BR")}`;
  } catch (error) { $("updated").textContent = "Falha na consulta: " + error.message; }
  finally { refreshing = false; $("refresh").disabled = false; }
}
$("accounts-form").addEventListener("submit", event => { event.preventDefault(); refresh(); });
$("instrument").addEventListener("change", refresh);
$("refresh").addEventListener("click", refresh);
$("command-form").addEventListener("submit", async event => {
  event.preventDefault(); const command = $("command").value.trim(); if (!command) return;
  $("send").disabled = true; $("command-status").textContent = "Publicando no Kafka…";
  try {
    const response = await fetch("/commands", {method: "POST", headers: {"Content-Type": "text/plain; charset=utf-8"}, body: command, signal: AbortSignal.timeout(15000)});
    const text = await response.text();
    if (response.status !== 202) throw new Error(`HTTP ${response.status}: ${text.trim()}`);
    $("command-status").textContent = "Publicado no Kafka. Aguarde o processamento; consulte events para aceitação ou rejeição.";
    $("command-status").className = "";
    const tags = Object.fromEntries(command.replaceAll("\x01", "|").split("|").filter(Boolean).map(tag => { const i = tag.indexOf("="); return [tag.slice(0, i), tag.slice(i + 1)]; }));
    if (tags["35"] === "D" && tags["54"] === "1" && tags["44"] === "100" && tags["11"]) lastRestingOrder = tags["11"];
    const item = document.createElement("li"); item.textContent = `${new Date().toLocaleTimeString("pt-BR")} · HTTP 202 · ${command}`; $("history").prepend(item);
    while ($("history").children.length > 20) $("history").lastChild.remove();
    refresh();
  } catch (error) { $("command-status").textContent = error.message + " Se houve timeout, o comando pode ter sido publicado; reenvie com o mesmo ID para evitar duplicação na sessão."; $("command-status").className = "error"; }
  finally { $("send").disabled = false; }
});
setInterval(() => { if ($("auto").checked && !document.hidden) refresh(); }, 2000);
refresh();
