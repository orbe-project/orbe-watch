# Protocolo da ponte

Um WebSocket entre o relógio e a ponte (`orbe_relogio.py`, no [orbe-desktop](https://github.com/orbe-project/orbe-desktop)). Mensagens de texto são uma linha cada; as binárias são PCM
`s16le` mono.

| Sentido | Mensagem | Quando |
|---|---|---|
| ponte → relógio | `desafio <sal em hex>` | Ao conectar. |
| relógio → ponte | `ola {"prova": "...", "nome": "...", "voz": true, "voz_pc": false}` | A prova do token. `voz` pede a resposta em áudio no relógio; `voz_pc`, também no PC. |
| ponte → relógio | `ola {"v": 1, "orbe": {...}, "tema": {...}, "microfone": true, "voz": true, "voz_pc": true, "agentes": [{"id", "nome", "instancias"}], "sessoes": [...], "abre_claude": true}` | Pareado: a aparência e o que a ponte aceita (`voz_pc`: o PC também tem voz, só no daemon; `agentes`: os instalados no PC, e `instancias` nos que têm uma sessão por instância do orbe, o Claude e os que rodam numa janela; `sessoes`: como no `sessoes` abaixo; `abre_claude`: falar numa vaga livre abre uma sessão, só no daemon). |
| ponte → relógio | `sessoes [{"agente", "vaga", "pid", "rotulo", "titulo", "pasta", "estado", "canal", "ouve"}]` | As sessões abertas no computador dos agentes com instâncias mudaram; as vagas contam por `agente` (sem ele, de uma ponte antiga: o Claude). `titulo`: o da conversa; `pasta`: onde foi aberta; `rotulo`: o nome antigo, para relógios de antes; `canal`: aberta pelo orbe; `ouve`: ouve o orbe (o hook armado, ou uma janela do orbe). |
| ponte → relógio | `show idle`, `state thinking`, `level 0.42 0.60`, `mic 0.3`, `line <texto>`, `hold 1`, `hide`, `clear` | As linhas do orbe, as mesmas do desktop. |
| ponte → relógio | `config {"orbe": {...}, "tema": {...}}` | O avatar, o glitch ou o tema mudaram no computador. |
| relógio → ponte | `touch down`, `touch up` | O dedo no orbe. |
| relógio → ponte | `toggle`, `trigger`, `dismiss`, `hold`, `release` | Os comandos do `orb_control`. |
| relógio → ponte | `agente <id>` | O agente do orbe em tela (vazio = Claude Code): a sessão aberta pelo relógio usa ele. |
| relógio → ponte | `vaga <k>`, `vaga` | A vaga do orbe em tela: com m orbes do mesmo agente, a instância k do j-ésimo é a vaga k·m + j (sem número: o agente dele não tem instâncias). A sessão aberta pelo relógio fala com a sessão dessa vaga. |
| relógio → ponte | `orbe <skin> <#rrggbb>`, `orbe <skin> -` | A skin e a cor da instância do orbe em tela (`-`: a do tema), para o orbe do computador seguir o relógio. |
| relógio → ponte | `historico` | Pede as sessões passadas do agente do orbe em tela (a ação Histórico dos toques). |
| ponte → relógio | `historico {"agente", "sessoes": [{"id", "titulo", "pasta", "quando"}], "erro"}` | A resposta, só a quem pediu: da mais recente para a mais velha; `quando` em segundos; `erro` quando o agente não lista. |
| relógio → ponte | `retomar <id>` | Retoma a sessão escolhida no orbe em tela (num agente com instâncias, na vaga dele). |
| os dois | `ajustes {"t": ..., "agentes": {...}, "voz": true, "tamanhos": {"anel": 0.9, ...}, "toques": ["abrir", "live", "encerrar", "nada"], ...}` | Os ajustes do app do relógio; vale o `t` (ms) mais novo. `tamanhos`: o de cada skin (sem a skin, vale o `tamanho` comum). `toques`, `live`, `fundo`, `ordem`, `sacudida`, `sair`, `sacudida_fora`, `sacudida_dentro`, `sair_fora`: `null` do computador é "não sei", e o relógio fica com o seu; o computador aprende os do relógio mesmo com o `t` dele mais velho. Calibração 0 volta ao padrão. |
| relógio → ponte | *(binário)* | A fala, em quadros de 30 ms a 16 kHz, enquanto o dedo segura o orbe (ou, na sessão aberta por `trigger`, enquanto ela ouve). |
| ponte → relógio | `voz 24000`, *(binário)*, `voz fim`, `voz corta` | A resposta em voz (taxa do áudio, o áudio, o fim, calar já). No orbe de pulso, e no daemon quando a sessão é do relógio. |
| relógio → ponte | `voz acabou` | O relógio tocou até o fim. |

Códigos de fechamento: `4401` token recusado, `4429` muitas tentativas,
`1013` cliente lento demais. A ponte guarda o estado do orbe e o reenvia a
quem conecta no meio de uma conversa. As linhas de nível (`level`, `mic`) são
descartadas para um relógio lento, para a imagem não atrasar.
