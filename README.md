# RustlandKillMoney

Inclui: dinheiro por kill (decrescente), recompensa diaria de 7 dias e air drops.

Dinheiro por kill de jogador (usa `eco give` do EssentialsX, a mesma economia do zMClans).

- Kill no MESMO jogador: 200, 100, 75, 50, 25. Depois disso paga 0.
- Passados 40 minutos da ultima kill paga naquele jogador, volta a 200.
- Kill entre membros do mesmo clan (zMClans) nao paga.
- Sem VIP. Sem aviso de "voce matou X vezes".
- Valores, tempo e comando ficam em `plugins/RustlandKillMoney/config.yml`.
- `/killmoney reload` recarrega a config (permissao `killmoney.admin`).

## Compilar (GitHub Actions)

1. Suba esta pasta inteira para um repositorio seu no GitHub (branch `main`).
2. Abra a aba **Actions**: o workflow "Build plugin" roda sozinho.
3. Quando ficar verde, o JAR fica em **Releases** (tag `latest`) e tambem em
   Actions > run > Artifacts.
4. Coloque o JAR em `plugins/` do servidor e reinicie.

## Recompensa diaria (/daily)

- `/daily` abre o menu dos 7 dias; clique no dia dourado para coletar.
- `/daily claim` coleta direto, sem abrir o menu. Aliases: `/diario`, `/recompensa`.
- Passou um dia (meia-noite, horario de Brasilia) sem coletar: volta ao dia 1.
- Depois do dia 7, volta ao dia 1.
- Premios, icones e mensagens: `plugins/RustlandKillMoney/daily.yml`.
- O jogador recebe um aviso ao entrar se tiver recompensa para coletar.

## Air drops

- 3 por dia (13h, 18h e 22h, horario de Brasilia), so se houver jogador online.
- Cai em ponto aleatorio do mundo `world`, entre 150 e 1500 blocos do spawn.
- Raridades: COMUM 55%, INCOMUM 28%, RARO 14%, EPICO 3%.
- Aviso no chat e titulo na tela com as coordenadas; feixe de particulas sobre o bau.
- Quem abrir primeiro ganha um bonus em dinheiro (por raridade). O bau some quando
  esvaziado ou apos 30 minutos.
- Placeholders para o TAB: `%rustland_airdrop_active%`, `_tier`, `_tier_color`, `_x`, `_y`,
  `_z`, `_coords`, `_time_left`, `_status`, `_next`.
- `/airdrop` mostra o status. Admin (`killmoney.admin`): `/airdrop spawn [common|uncommon|rare|epic]`,
  `/airdrop end`, `/airdrop reload`.
- Tudo configuravel em `plugins/RustlandKillMoney/airdrop.yml`.
