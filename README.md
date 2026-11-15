# RustlandKillMoney

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
