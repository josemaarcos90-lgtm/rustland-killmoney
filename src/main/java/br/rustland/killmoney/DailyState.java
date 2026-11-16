package br.rustland.killmoney;

import java.time.LocalDate;

/**
 * Estado da recompensa diaria de um jogador, calculado so a partir da ultima coleta.
 *
 * - claimedCount: quantos dias (1..N) aparecem como coletados no menu
 * - claimableDay: dia que pode ser coletado agora (0 = ja coletou hoje)
 *
 * Regras:
 *  - nunca coletou: pode coletar o dia 1
 *  - ja coletou hoje: nada a coletar
 *  - coletou ontem: segue para o proximo dia (depois do ultimo dia do ciclo volta ao dia 1)
 *  - passou um dia ou mais sem coletar: volta ao dia 1
 */
final class DailyState {

    final int claimedCount;
    final int claimableDay;
    final boolean claimedToday;

    private DailyState(int claimedCount, int claimableDay, boolean claimedToday) {
        this.claimedCount = claimedCount;
        this.claimableDay = claimableDay;
        this.claimedToday = claimedToday;
    }

    static DailyState compute(LocalDate lastDate, int lastDay, LocalDate today, int cycle) {
        if (lastDate == null || lastDay <= 0) {
            return new DailyState(0, 1, false);
        }
        if (!lastDate.isBefore(today)) {
            // coletou hoje (ou o relogio andou para tras): nada a coletar
            return new DailyState(Math.min(lastDay, cycle), 0, true);
        }
        if (lastDate.plusDays(1).equals(today)) {
            if (lastDay >= cycle) {
                return new DailyState(0, 1, false);
            }
            return new DailyState(lastDay, lastDay + 1, false);
        }
        return new DailyState(0, 1, false);
    }
}
