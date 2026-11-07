# OpenANC 🎧 — Dual Mic Experimental v1.1.0

Aplicativo Android experimental de antirruído em fones com fio.

## Como funciona

- Entrada principal: microfone do headset (quando disponível) ou do celular.
- Modo **Automático: fone + celular** tenta abrir uma segunda entrada independente.
- Verifica o roteamento real: não considera dois AudioRecords como dois microfones distintos se ambos forem roteados à mesma entrada.
- Faz fusão cautelosa (até 12% do segundo microfone) apenas se as capturas forem temporalmente recentes e apresentarem correlação positiva alta (≥0,90). Caso contrário, usa apenas o microfone principal.
- Mostra o estado da segunda entrada. Alguns aparelhos podem negar duas captações ou entregar silêncio.
- Limita a amplitude da onda inversa e interrompe a reprodução ao desconectar o fone.

**Importante:** correlação entre microfones não representa calibração acústica no tímpano. Sem microfone dentro da orelha, medida de latência e identificação do caminho secundário, não há garantia de cancelamento real, especialmente de vozes próximas. O efeito pode ser nulo ou até aumentar o ruído.

Use o volume baixo; pare imediatamente se ouvir apito, aumento de ruído ou desconforto. Não use ao dirigir, na rua ou próximo de equipamentos perigosos.

## Instalação

Baixe o APK em **Releases**. Fonte aberta. Android 9+. Não utiliza Bluetooth.

## Compilação

A cada push na `main`, o GitHub Actions compila o APK de teste e publica em Release `v1.1.0`. Código-fonte em `app/src/main/java/com/openanc/app/`.

## Arquitetura

Uso de `AudioRecord` e `AudioTrack` em Java e dois fluxos independentes de leitura. Não implementa ANC profissional, filtragem adaptativa FxLMS calibrada nem correção de latência de hardware.
