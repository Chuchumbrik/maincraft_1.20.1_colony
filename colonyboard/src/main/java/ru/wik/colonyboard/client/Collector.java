package ru.wik.colonyboard.client;

import com.minecolonies.api.colony.ICitizenDataView;
import com.minecolonies.api.colony.IColonyView;
import com.minecolonies.api.colony.buildings.views.IBuildingView;
import com.minecolonies.api.colony.interactionhandling.ChatPriority;
import com.minecolonies.api.colony.interactionhandling.IInteractionResponseHandler;
import com.minecolonies.api.colony.requestsystem.request.IRequest;
import com.minecolonies.api.colony.requestsystem.requestable.IDeliverable;
import com.minecolonies.api.entity.citizen.VisibleCitizenStatus;
import com.minecolonies.api.entity.citizen.happiness.IHappinessModifier;
import com.minecolonies.core.colony.buildings.moduleviews.WorkerBuildingModuleView;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import ru.wik.colonyboard.model.Filter;
import ru.wik.colonyboard.model.Row;
import ru.wik.colonyboard.model.Search;

import java.util.*;

/** Reads client-side MineColonies views into board rows. Every call is defensive: views can be partial. */
public final class Collector {
    public static final double UNHAPPY_THRESHOLD = 7.0;
    /** 1.7: request kind stored in Row.ref (0 = any, for the filter). */
    public static final int KIND_ANY = 0, KIND_BUILD = 1, KIND_TOOL = 2, KIND_WORK = 3;
    public static final String[] KINDS = {"any", "build", "tool", "work"};
    private Collector() {}

    static Component citizenName(ICitizenDataView c) {
        return Component.literal(c.getName());
    }

    static Component job(ICitizenDataView c) {
        try {
            MutableComponent j = c.getJobComponent();
            if (j != null && c.getJob() != null && !c.getJob().isEmpty()) return j;
        } catch (Throwable ignored) {}
        return Component.translatable("colonyboard.nojob");
    }

    public static List<Row> complaints(IColonyView colony) {
        List<Row> rows = new ArrayList<>();
        int blockPrio = ChatPriority.BLOCKING.getPriority();
        for (ICitizenDataView c : colony.getCitizens().values()) {
            List<IInteractionResponseHandler> list;
            try { list = c.getOrderedInteractions(); } catch (Throwable t) { continue; }
            if (list == null) continue;
            for (IInteractionResponseHandler h : list) {
                try {
                    if (!h.isPrimary()) continue;
                    int prio = h.getPriority().getPriority();
                    boolean blocking = prio >= blockPrio;
                    Component title = citizenName(c).copy().append(" — ").append(job(c));
                    rows.add(new Row(title, h.getInquiry(), c.getWorkBuilding(), blocking, prio,
                        c.getEntityId(), c.getPosition()));
                } catch (Throwable ignored) {}
            }
        }
        return rows;
    }

    public static List<Row> requests(IColonyView colony) {
        List<Row> rows = new ArrayList<>();
        Set<Object> seen = new HashSet<>();
        for (IBuildingView b : buildings(colony)) {
            List<IRequest<?>> reqs = new ArrayList<>();
            try { reqs.addAll(b.getOpenRequestsOfBuilding()); } catch (Throwable ignored) {}
            try {
                for (Integer cid : b.getOpenRequestsByCitizen().keySet()) {
                    ICitizenDataView cv = colony.getCitizen(cid);
                    if (cv != null) reqs.addAll(b.getOpenRequests(cv));
                }
            } catch (Throwable ignored) {}
            for (IRequest<?> r : reqs) {
                try {
                    if (!seen.add(r.getId())) continue;
                    int count = 0;
                    Object req = r.getRequest();
                    if (req instanceof IDeliverable d) count = d.getCount();
                    String kind = req instanceof com.minecolonies.api.colony.requestsystem.requestable.Tool ? "tool" : (isBuilder(b) ? "build" : "work");
                    MutableComponent title = Component.literal("[").append(Component.translatable("colonyboard.kind." + kind)).append("] ")
                        .append(r.getShortDisplayString().copy());
                    if (count > 0) title.append(" ×" + count);
                    MutableComponent detail = buildingName(b).copy().append(" · ")
                        .append(Component.translatable("colonyboard.state." + r.getState().name()));
                    try { detail.append(" · ").append(r.getRequester().getRequesterDisplayName(colony.getRequestManager(), r)); }
                    catch (Throwable ignored) {}
                    Object ident = r.getId().getIdentifier();
                    java.util.UUID rid = ident instanceof java.util.UUID u ? u : java.util.UUID.nameUUIDFromBytes(String.valueOf(ident).getBytes());
                    rows.add(new Row(title, detail, b.getID(), false, r.getState().ordinal(), -1, null, rid, -1,
                        kind.equals("build") ? KIND_BUILD : kind.equals("tool") ? KIND_TOOL : KIND_WORK));
                } catch (Throwable ignored) {}
            }
        }
        return rows;
    }

    public static List<Row> happiness(IColonyView colony) {
        List<Row> rows = new ArrayList<>();
        for (ICitizenDataView c : colony.getCitizens().values()) {
            double h = c.getHappiness();
            if (h >= UNHAPPY_THRESHOLD) continue;
            Component reason = Component.translatable("colonyboard.nofactor");
            try {
                List<String> ids = c.getHappinessHandler().getModifiers();
                double[] f = new double[ids.size()], w = new double[ids.size()];
                for (int i = 0; i < ids.size(); i++) {
                    IHappinessModifier m = c.getHappinessHandler().getModifier(ids.get(i));
                    try { f[i] = m.getFactor(null); } catch (Throwable t) { f[i] = 1.0; }
                    w[i] = m.getWeight();
                }
                int worst = Filter.worstFactor(f, w);
                if (worst >= 0) reason = Component.translatable("com.minecolonies.coremod.gui.townhall.happiness." + ids.get(worst));
            } catch (Throwable ignored) {}
            Component title = citizenName(c).copy().append(" — ").append(job(c));
            Component detail = Component.translatable("colonyboard.happiness", String.format(Locale.ROOT, "%.1f", h), reason);
            rows.add(new Row(title, detail, c.getWorkBuilding(), h < 4.0, (int) ((10 - h) * 10), c.getEntityId(), c.getPosition()));
        }
        return rows;
    }

    public static List<Row> idle(IColonyView colony) {
        List<Row> rows = new ArrayList<>();
        for (ICitizenDataView c : colony.getCitizens().values()) {
            String key = null; boolean blocking = false;
            try {
                if (c.isSick()) { key = "colonyboard.sick"; blocking = true; }
                else if (!c.isChild() && (c.getJob() == null || c.getJob().isEmpty()) && c.getWorkBuilding() == null) key = "colonyboard.unemployed";
                else if (c.getVisibleStatus() == VisibleCitizenStatus.SLEEP) key = "colonyboard.sleeping";
            } catch (Throwable ignored) {}
            if (key == null) continue;
            rows.add(new Row(citizenName(c).copy().append(" — ").append(job(c)), Component.translatable(key),
                c.getWorkBuilding(), blocking, blocking ? 2 : 1, c.getEntityId(), c.getPosition()));
        }
        for (IBuildingView b : buildings(colony)) {
            try {
                for (WorkerBuildingModuleView m : b.getModuleViews(WorkerBuildingModuleView.class)) {
                    if (m.getAssignedCitizens().isEmpty()) {
                        rows.add(new Row(buildingName(b), Component.translatable("colonyboard.noworker")
                            .append(": ").append(Component.translatable(m.getJobDisplayName())), b.getID(), true, 3, -1, b.getID()));
                    }
                }
            } catch (Throwable ignored) {}
        }
        return rows;
    }

    public static List<Row> construction(IColonyView colony) {
        List<Row> rows = new ArrayList<>();
        try {
            for (com.minecolonies.api.colony.workorders.IWorkOrderView w : colony.getWorkOrders()) {
                MutableComponent title = w.getDisplayName().copy();
                title.append(" (" + w.getCurrentLevel() + "→" + w.getTargetLevel() + ")");
                MutableComponent detail = Component.translatable("colonyboard.wo." + w.getWorkOrderType().name().toLowerCase(Locale.ROOT));
                boolean waiting = !w.isClaimed(), missing = false;
                if (waiting) detail.append(" · ").append(Component.translatable("colonyboard.wo.unclaimed"));
                else {
                    IBuildingView hut = colony.getClientBuildingManager().getBuilding(w.getClaimedBy());
                    detail.append(" · ").append(Component.translatable("colonyboard.wo.builder",
                        hut != null ? buildingName(hut) : Component.literal(w.getClaimedBy().toShortString())));
                    if (hut != null) {
                        int open = 0;
                        try { open = hut.getOpenRequestsOfBuilding().size(); } catch (Throwable ignored) {}
                        for (ICitizenDataView c : colony.getCitizens().values())
                            if (w.getClaimedBy().equals(c.getWorkBuilding())) try { open += hut.getOpenRequests(c).size(); } catch (Throwable ignored) {}
                        if (open > 0) { missing = true; detail.append(" · ").append(Component.translatable("colonyboard.wo.missing", open)); }
                    }
                }
                detail.append(" · ").append(Component.translatable("colonyboard.wo.prio", w.getPriority()));
                rows.add(new Row(title, detail, w.isClaimed() ? w.getClaimedBy() : w.getLocation(), waiting || missing, w.getPriority(), -1, w.getLocation(),
                    null, -1, w.getID()));
            }
        } catch (Throwable ignored) {}
        return rows;
    }

    /** Colony summary + citizens who are hungry or hurt. */
    public static List<Row> summary(IColonyView colony, ru.wik.colonyboard.Net.Stats stats) {
        List<Row> rows = new ArrayList<>();
        int unemployed = 0, sick = 0, hungry = 0, hurt = 0, requests = 0, adults = 0;
        for (ICitizenDataView c : colony.getCitizens().values()) {
            try {
                if (!c.isChild()) adults++;
                if (c.isSick()) sick++;
                if (!c.isChild() && (c.getJob() == null || c.getJob().isEmpty()) && c.getWorkBuilding() == null) unemployed++;
                if (c.getSaturation() < 5) hungry++;
                if (c.getMaxHealth() > 0 && c.getHealth() / c.getMaxHealth() < 0.5) hurt++;
            } catch (Throwable ignored) {}
        }
        try { requests = requests(colony).size(); } catch (Throwable ignored) {}
        int noWorker = 0;
        for (Row r : idle(colony)) if (r.detail().getString().startsWith(Component.translatable("colonyboard.noworker").getString())) noWorker++;
        int housing = 0;
        for (IBuildingView b : buildings(colony)) {
            try {
                for (com.minecolonies.core.colony.buildings.moduleviews.LivingBuildingModuleView m : b.getModuleViews(com.minecolonies.core.colony.buildings.moduleviews.LivingBuildingModuleView.class))
                    housing += m.getMax();
            } catch (Throwable ignored) {}
        }
        rows.add(stat("colonyboard.sum.citizens", colony.getCitizenCount() + " / " + colony.getCitizenCountLimit(), false));
        rows.add(stat("colonyboard.sum.housing", String.valueOf(housing), housing > 0 && housing < colony.getCitizenCount()));
        rows.add(stat("colonyboard.sum.happiness", String.format(Locale.ROOT, "%.1f", colony.getOverallHappiness()), colony.getOverallHappiness() < 5));
        rows.add(stat("colonyboard.sum.unemployed", unemployed + " / " + adults, unemployed > 0));
        rows.add(stat("colonyboard.sum.noworker", String.valueOf(noWorker), noWorker > 0));
        rows.add(stat("colonyboard.sum.sick", String.valueOf(sick), sick > 0));
        rows.add(stat("colonyboard.sum.hungry", String.valueOf(hungry), hungry > 0));
        rows.add(stat("colonyboard.sum.hurt", String.valueOf(hurt), hurt > 0));
        rows.add(stat("colonyboard.sum.requests", String.valueOf(requests), false));
        int wo = 0; try { wo = colony.getWorkOrders().size(); } catch (Throwable ignored) {}
        rows.add(stat("colonyboard.sum.workorders", String.valueOf(wo), false));
        boolean raid = false; try { raid = colony.isRaiding(); } catch (Throwable ignored) {}
        String danger = stats != null && stats.colonyId() == colony.getID() && !stats.danger().isEmpty() ? stats.danger() : (raid ? "colonyboard.state.mcraid" : null);
        rows.add(new Row(Component.translatable("colonyboard.sum.danger"), danger == null ? Component.translatable("colonyboard.state.peace") : Component.translatable(danger),
            null, danger != null, 100, -1, null));
        if (stats != null && stats.colonyId() == colony.getID()) {
            if (stats.treasury() >= 0) rows.add(stat("colonyboard.sum.treasury", stats.treasury() + " ◎", false));
            if (stats.wallet() >= 0) rows.add(stat("colonyboard.sum.wallet", String.format(Locale.ROOT, "%.0f ◎", stats.wallet()), false));
        } else rows.add(stat("colonyboard.sum.noserver", "", false));
        // individual hungry / hurt citizens
        for (ICitizenDataView c : colony.getCitizens().values()) {
            try {
                double hp = c.getMaxHealth() > 0 ? c.getHealth() / c.getMaxHealth() : 1;
                if (c.getSaturation() >= 5 && hp >= 0.5) continue;
                Component d = Component.translatable("colonyboard.sum.vitals", String.format(Locale.ROOT, "%.0f", c.getSaturation()),
                    String.format(Locale.ROOT, "%.0f/%.0f", c.getHealth(), c.getMaxHealth()));
                rows.add(new Row(citizenName(c).copy().append(" — ").append(job(c)), d, c.getWorkBuilding(),
                    c.getSaturation() < 2 || hp < 0.25, -1, c.getEntityId(), c.getPosition()));
            } catch (Throwable ignored) {}
        }
        return rows;
    }

    public static List<Row> finance(IColonyView colony, ru.wik.colonyboard.Net.Stats st) {
        List<Row> rows = new ArrayList<>();
        if (st == null || st.colonyId() != colony.getID() || st.finDeny() == null) { rows.add(stat("colonyboard.sum.noserver", "", false)); return rows; }
        rows.add(stat("colonyboard.sum.wallet", st.wallet() >= 0 ? String.format(Locale.ROOT, "%.0f ◎", st.wallet()) : "?", false));
        rows.add(stat("colonyboard.sum.treasury", st.treasury() < 0 ? "?" : st.treasury() + (st.capacity() > 0 ? " / " + st.capacity() : "") + " ◎", false));
        if (st.tax() != Long.MIN_VALUE) {
            if (st.tax() < 0) rows.add(stat("colonyboard.fin.debtline", (-st.tax()) + " ◎", true));
            else rows.add(stat("colonyboard.fin.taxline", st.tax() + " ◎", false));
        }
        rows.add(new Row(Component.translatable("colonyboard.sum.danger"), st.danger().isEmpty() ? Component.translatable("colonyboard.state.peace")
            : Component.translatable("colonyboard.fin.warlock", Component.translatable(st.danger())), null, !st.danger().isEmpty(), 100, -1, null));
        rows.add(stat("colonyboard.fin.role", Component.translatable(st.manager() ? "colonyboard.fin.role.manager" : "colonyboard.fin.role.member").getString(), false));
        rows.add(new Row(Component.translatable("colonyboard.fin.journal"), Component.translatable(st.journal().isEmpty() ? "colonyboard.fin.journal.empty" : "colonyboard.fin.journal.hint"), null, false, 2000, -1, null));
        for (Component j : st.journal()) rows.add(new Row(j, Component.empty(), null, false, 3000, -1, null));
        return rows;
    }

    // ------------------------------------------------------------------ 1.7 J upgrade

    /** Dashboard jump targets (BoardScreen tab indices are resolved there by name). */
    public static final int J_COMPLAINTS = 1, J_REQUESTS = 2, J_BUILD = 3, J_STOCK = 4, J_CITIZENS = 5, J_FIN = 6, J_WAR = 7;

    private static Row dash(String key, long count, boolean bad, int jump, Component detail) {
        return new Row(Component.translatable(key, count), detail, null, bad && count > 0, bad && count > 0 ? (int) Math.min(1000, count) : -1, -1, null, null, jump, -1);
    }

    static boolean isGuard(ICitizenDataView c) {
        try {
            String j = c.getJob();
            if (j == null) return false;
            j = j.toLowerCase(Locale.ROOT);
            return j.contains("knight") || j.contains("ranger") || j.contains("druid") || j.contains("guard");
        } catch (Throwable t) { return false; }
    }

    /** "What is on fire": one line per problem, click jumps to the tab that fixes it. Problems first. */
    public static List<Row> dashboard(IColonyView colony, ru.wik.colonyboard.Net.Stats st, ru.wik.colonyboard.Net.DashData d) {
        List<Row> rows = new ArrayList<>();
        int unemployed = 0, homeless = 0, unhappy = 0, hungry = 0, sick = 0, hurt = 0, blockingComplaints = 0;
        for (ICitizenDataView c : colony.getCitizens().values()) {
            try {
                if (c.isSick()) sick++;
                if (!c.isChild() && (c.getJob() == null || c.getJob().isEmpty()) && c.getWorkBuilding() == null) unemployed++;
                if (c.getHomeBuilding() == null) homeless++;
                if (c.getHappiness() < UNHAPPY_THRESHOLD) unhappy++;
                if (c.getSaturation() < 5) hungry++;
                if (c.getMaxHealth() > 0 && c.getHealth() / c.getMaxHealth() < 0.5) hurt++;
            } catch (Throwable ignored) {}
        }
        for (Row r : complaints(colony)) if (r.blocking()) blockingComplaints++;
        int noWorker = 0;
        for (Row r : idle(colony)) if (r.citizenEntityId() < 0 && r.blocking()) noWorker++;
        int woWaiting = 0, woMissing = 0, woAll = 0;
        try {
            for (com.minecolonies.api.colony.workorders.IWorkOrderView w : colony.getWorkOrders()) {
                woAll++;
                if (!w.isClaimed()) { woWaiting++; continue; }
                IBuildingView hut = colony.getClientBuildingManager().getBuilding(w.getClaimedBy());
                if (hut == null) continue;
                int open = 0;
                try { open = hut.getOpenRequestsOfBuilding().size(); } catch (Throwable ignored) {}
                for (ICitizenDataView c : colony.getCitizens().values())
                    if (w.getClaimedBy().equals(c.getWorkBuilding())) try { open += hut.getOpenRequests(c).size(); } catch (Throwable ignored) {}
                if (open > 0) woMissing++;
            }
        } catch (Throwable ignored) {}
        boolean server = d != null && d.colonyId() == colony.getID();

        // requests
        if (server) {
            long n = d.n("stuck", 0), buy = d.n("stuckBuyable", 0), cost = d.n("stuckCost", 0);
            rows.add(dash("colonyboard.dash.stuck", n, true, J_REQUESTS, n == 0 ? Component.translatable("colonyboard.dash.ok")
                : Component.translatable("colonyboard.dash.stuck.detail", buy, cost)));
        } else {
            int open = 0; try { open = requests(colony).size(); } catch (Throwable ignored) {}
            rows.add(dash("colonyboard.dash.open", open, false, J_REQUESTS, Component.translatable("colonyboard.dash.noserver")));
        }
        rows.add(dash("colonyboard.dash.wo.missing", woMissing, true, J_BUILD, Component.translatable("colonyboard.dash.wo.detail", woAll, woWaiting)));
        if (woWaiting > 0) rows.add(dash("colonyboard.dash.wo.waiting", woWaiting, true, J_BUILD, Component.translatable("colonyboard.dash.wo.waiting.detail")));
        rows.add(dash("colonyboard.dash.complaints", blockingComplaints, true, J_COMPLAINTS, Component.translatable("colonyboard.dash.complaints.detail")));
        rows.add(dash("colonyboard.dash.hungry", hungry, true, J_CITIZENS, Component.translatable("colonyboard.dash.hungry.detail", hurt, sick)));
        rows.add(dash("colonyboard.dash.unemployed", unemployed, true, J_CITIZENS, Component.translatable("colonyboard.dash.unemployed.detail", noWorker)));
        rows.add(dash("colonyboard.dash.homeless", homeless, true, J_CITIZENS, Component.translatable("colonyboard.dash.homeless.detail",
            colony.getCitizenCount(), colony.getCitizenCountLimit())));
        rows.add(dash("colonyboard.dash.unhappy", unhappy, true, J_CITIZENS, Component.translatable("colonyboard.dash.unhappy.detail",
            String.format(Locale.ROOT, "%.1f", colony.getOverallHappiness()))));
        if (server && d.n("warehouse", 0) == 1) {
            int shortN = 0; for (var x : d.shortages()) if (x.have() < x.need()) shortN++;
            rows.add(dash("colonyboard.dash.stock", shortN, true, J_STOCK, Component.translatable("colonyboard.dash.stock.detail", d.surplus().size())));
        } else if (server) rows.add(dash("colonyboard.dash.nowarehouse", 1, true, J_STOCK, Component.translatable("colonyboard.dash.nowarehouse.detail")));
        // money
        if (st != null && st.colonyId() == colony.getID() && st.tax() != Long.MIN_VALUE) {
            if (st.tax() < 0) rows.add(dash("colonyboard.dash.debt", -st.tax(), true, J_FIN, Component.translatable("colonyboard.dash.debt.detail", server ? d.n("debtCycles", 0) : 0)));
            else rows.add(dash("colonyboard.dash.tax", st.tax(), false, J_FIN, server && d.has("taxNext")
                ? Component.translatable("colonyboard.dash.tax.next", mins(d.n("taxNext", 0))) : Component.translatable("colonyboard.dash.tax.claim")));
        }
        // war
        String danger = server ? d.s("danger") : (st != null && st.colonyId() == colony.getID() ? st.danger() : "");
        if (!danger.isEmpty()) rows.add(new Row(Component.translatable("colonyboard.dash.danger", Component.translatable(danger)),
            Component.translatable("colonyboard.dash.danger.detail"), null, true, 2000, -1, null, null, J_WAR, -1));
        else if (server && d.has("guards")) {
            long g = d.n("guards", 0), need = d.n("minGuardsRaid", 0);
            rows.add(new Row(Component.translatable("colonyboard.dash.guards", g, d.n("towers", 0)),
                Component.translatable(g < need ? "colonyboard.dash.guards.low" : "colonyboard.dash.guards.ok", need), null, g < need, g < need ? 5 : -1, -1, null, null, J_WAR, -1));
        }
        rows.sort(Comparator.comparing((Row r) -> !r.blocking()).thenComparing(Row::sortKey, Comparator.reverseOrder()));
        return rows;
    }

    static Component mins(long sec) {
        if (sec < 60) return Component.translatable("colonyboard.time.s", sec);
        long m = sec / 60;
        return m < 60 ? Component.translatable("colonyboard.time.m", m) : Component.translatable("colonyboard.time.hm", m / 60, m % 60);
    }

    /** Citizens tab. cat: 0 all problems, 1 no job, 2 no home, 3 unhappy, 4 hungry/hurt, 5 sick, 6 everyone. */
    public static final int CAT_N = 7;
    public static List<Row> citizens(IColonyView colony, int cat, String query) {
        List<Row> rows = new ArrayList<>();
        for (ICitizenDataView c : colony.getCitizens().values()) {
            try {
                boolean noJob = !c.isChild() && (c.getJob() == null || c.getJob().isEmpty()) && c.getWorkBuilding() == null;
                boolean noHome = c.getHomeBuilding() == null;
                double h = c.getHappiness();
                boolean unhappy = h < UNHAPPY_THRESHOLD;
                double hp = c.getMaxHealth() > 0 ? c.getHealth() / c.getMaxHealth() : 1;
                boolean hungry = c.getSaturation() < 5 || hp < 0.5;
                boolean sick = c.isSick();
                boolean show = switch (cat) {
                    case 1 -> noJob; case 2 -> noHome; case 3 -> unhappy; case 4 -> hungry; case 5 -> sick; case 6 -> true;
                    default -> noJob || noHome || unhappy || hungry || sick;
                };
                if (!show) continue;
                MutableComponent det = Component.empty();
                List<Component> tags = new ArrayList<>();
                if (sick) tags.add(Component.translatable("colonyboard.cit.sick"));
                if (noJob) tags.add(Component.translatable("colonyboard.cit.nojob"));
                if (noHome) tags.add(Component.translatable("colonyboard.cit.nohome"));
                if (hungry) tags.add(Component.translatable("colonyboard.sum.vitals", String.format(Locale.ROOT, "%.0f", c.getSaturation()),
                    String.format(Locale.ROOT, "%.0f/%.0f", c.getHealth(), c.getMaxHealth())));
                Component reason = null;
                if (unhappy) {
                    try {
                        List<String> ids = c.getHappinessHandler().getModifiers();
                        double[] f = new double[ids.size()], w = new double[ids.size()];
                        for (int i = 0; i < ids.size(); i++) {
                            IHappinessModifier m = c.getHappinessHandler().getModifier(ids.get(i));
                            try { f[i] = m.getFactor(null); } catch (Throwable t) { f[i] = 1.0; }
                            w[i] = m.getWeight();
                        }
                        int worst = Filter.worstFactor(f, w);
                        if (worst >= 0) reason = Component.translatable("com.minecolonies.coremod.gui.townhall.happiness." + ids.get(worst));
                    } catch (Throwable ignored) {}
                }
                tags.add(Component.translatable("colonyboard.cit.happy", String.format(Locale.ROOT, "%.1f", h), reason == null ? Component.empty() : Component.literal(" (").append(reason).append(")")));
                for (int i = 0; i < tags.size(); i++) { if (i > 0) det.append(" · "); det.append(tags.get(i)); }
                Component title = citizenName(c).copy().append(" — ").append(job(c));
                if (!ru.wik.colonyboard.model.Search.matches(query, title.getString(), det.getString())) continue;
                int sev = (sick ? 40 : 0) + (noHome ? 20 : 0) + (noJob ? 15 : 0) + (int) ((10 - h) * 3) + (hungry ? 25 : 0);
                rows.add(new Row(title, det, c.getWorkBuilding(), sick || h < 4 || c.getSaturation() < 2 || hp < 0.25 || noHome, sev, c.getEntityId(), c.getPosition()));
            } catch (Throwable ignored) {}
        }
        if (cat == 0 || cat == 1) {
            for (Row r : idle(colony)) if (r.citizenEntityId() < 0 && Search.matches(query, r.title().getString(), r.detail().getString())) rows.add(r);
        }
        return rows;
    }

    /** Warehouse tab: shortages of stuck requests (need vs have), then piles nobody asks for. ref = index into surplus (market button). */
    public static List<Row> warehouse(IColonyView colony, ru.wik.colonyboard.Net.DashData d, String query) {
        List<Row> rows = new ArrayList<>();
        if (d == null || d.colonyId() != colony.getID()) { rows.add(stat("colonyboard.dash.noserver", "", false)); return rows; }
        if (d.n("warehouse", 0) != 1) rows.add(new Row(Component.translatable("colonyboard.dash.nowarehouse", 1), Component.translatable("colonyboard.dash.nowarehouse.detail"), null, true, 0, -1, null));
        rows.add(new Row(Component.translatable("colonyboard.stock.short.h", d.shortages().size()), Component.translatable("colonyboard.stock.short.hint"), null, false, 0, -1, null));
        for (var x : d.shortages()) {
            String name = x.item().getHoverName().getString();
            if (!Search.matches(query, name)) continue;
            int miss = Math.max(0, x.need() - x.have());
            rows.add(new Row(Component.literal(name + " ×" + x.need()), Component.translatable(miss > 0 ? "colonyboard.stock.short.line" : "colonyboard.stock.short.enough", x.have(), miss),
                null, miss > 0, miss, -1, null));
        }
        if (d.shortages().isEmpty()) rows.add(new Row(Component.translatable("colonyboard.stock.short.none"), Component.empty(), null, false, 0, -1, null));
        rows.add(new Row(Component.translatable("colonyboard.stock.surplus.h", d.surplus().size()), Component.translatable("colonyboard.stock.surplus.hint", d.n("surplusMin", 512)), null, false, 0, -1, null));
        for (int i = 0; i < d.surplus().size(); i++) {
            var x = d.surplus().get(i);
            String name = x.item().getHoverName().getString();
            if (!Search.matches(query, name)) continue;
            rows.add(new Row(Component.literal(name), Component.translatable("colonyboard.stock.surplus.line", x.have()), null, false, x.have(), -1, null, null, -1, i));
        }
        if (d.surplus().isEmpty()) rows.add(new Row(Component.translatable("colonyboard.stock.surplus.none"), Component.empty(), null, false, 0, -1, null));
        return rows;
    }

    /** Taxes & treasury block appended to the finance tab (WarNTaxes facts from the server). */
    public static List<Row> taxes(ru.wik.colonyboard.Net.DashData d, ru.wik.colonyboard.Net.AutoState a, int colonyId) {
        List<Row> rows = new ArrayList<>();
        if (d == null || d.colonyId() != colonyId) return rows;
        if (d.has("taxInterval")) {
            long iv = d.n("taxInterval", 0);
            Component det = d.has("taxNext") ? Component.translatable("colonyboard.tax.next", mins(d.n("taxNext", 0)), iv)
                : Component.translatable("colonyboard.tax.every", iv);
            rows.add(new Row(Component.translatable("colonyboard.tax.cycle"), det, null, d.n("taxFrozen", 0) == 1, 900, -1, null));
        }
        if (d.n("taxFrozen", 0) == 1) rows.add(new Row(Component.translatable("colonyboard.tax.frozen"), Component.translatable("colonyboard.tax.frozen.detail"), null, true, 900, -1, null));
        if (d.has("policy") || d.has("policyMult")) rows.add(new Row(Component.translatable("colonyboard.tax.policy"),
            Component.literal((d.s("policy").isEmpty() ? "—" : d.s("policy")) + " · ×" + String.format(Locale.ROOT, "%.2f", d.n("policyMult", 1000) / 1000.0)), null, false, 900, -1, null));
        if (d.has("warTaxMult") && d.n("warTaxMult", 1000) != 1000) rows.add(new Row(Component.translatable("colonyboard.tax.warmult"),
            Component.literal("×" + String.format(Locale.ROOT, "%.2f", d.n("warTaxMult", 1000) / 1000.0)), null, d.n("warTaxMult", 1000) < 1000, 900, -1, null));
        if (d.n("debtCycles", 0) > 0) rows.add(new Row(Component.translatable("colonyboard.tax.debtcycles", d.n("debtCycles", 0), d.n("debtAbandon", 0)),
            Component.translatable("colonyboard.tax.debtlimit", d.n("debtLimit", 0)), null, true, 900, -1, null));
        if (d.has("maxTax")) rows.add(new Row(Component.translatable("colonyboard.tax.max"), Component.literal(d.n("maxTax", 0) + " ◎"), null, false, 900, -1, null));
        if (a != null && a.available() && a.colonyId() == colonyId)
            rows.add(new Row(Component.translatable("colonyboard.tax.auto"), Component.translatable(a.on() ? "colonyboard.tax.auto.on" : "colonyboard.tax.auto.off", a.spent(), a.budget()), null, false, 900, -1, null));
        return rows;
    }

    /** War readiness: danger, guards vs WarNTaxes minimums, towers, treasury vs war cost, exhaustion timers, economy status, hurt guards. */
    public static List<Row> war(IColonyView colony, ru.wik.colonyboard.Net.Stats st, ru.wik.colonyboard.Net.DashData d) {
        List<Row> rows = new ArrayList<>();
        boolean server = d != null && d.colonyId() == colony.getID();
        String danger = server ? d.s("danger") : (st != null && st.colonyId() == colony.getID() ? st.danger() : "");
        rows.add(new Row(Component.translatable("colonyboard.sum.danger"), danger.isEmpty() ? Component.translatable("colonyboard.state.peace") : Component.translatable(danger),
            null, !danger.isEmpty(), 0, -1, null));
        int guardsClient = 0, guardsHurt = 0;
        List<Row> hurtRows = new ArrayList<>();
        for (ICitizenDataView c : colony.getCitizens().values()) {
            if (!isGuard(c)) continue;
            guardsClient++;
            try {
                double hp = c.getMaxHealth() > 0 ? c.getHealth() / c.getMaxHealth() : 1;
                if (hp < 0.75 || c.isSick()) {
                    guardsHurt++;
                    hurtRows.add(new Row(citizenName(c).copy().append(" — ").append(job(c)), Component.translatable("colonyboard.sum.vitals",
                        String.format(Locale.ROOT, "%.0f", c.getSaturation()), String.format(Locale.ROOT, "%.0f/%.0f", c.getHealth(), c.getMaxHealth())),
                        c.getWorkBuilding(), hp < 0.4, 0, c.getEntityId(), c.getPosition()));
                }
            } catch (Throwable ignored) {}
        }
        long guards = server && d.has("guards") ? d.n("guards", 0) : guardsClient;
        if (server && d.has("minGuardsWar")) {
            long war = d.n("minGuardsWar", 0), raid = d.n("minGuardsRaid", 0);
            rows.add(new Row(Component.translatable("colonyboard.war.guards", guards), Component.translatable("colonyboard.war.guards.need", raid, war, guardsHurt),
                null, guards < raid, 0, -1, null));
        } else rows.add(new Row(Component.translatable("colonyboard.war.guards", guards), Component.translatable("colonyboard.war.guards.hurt", guardsHurt), null, false, 0, -1, null));
        if (server && d.has("towers")) rows.add(new Row(Component.translatable("colonyboard.war.towers", d.n("towers", 0)),
            Component.translatable("colonyboard.war.towers.boost", d.n("towersBoost", 0)), null, false, 0, -1, null));
        if (server) {
            long tr = d.n("treasury", -1), req = d.n("warRequired", -1);
            if (tr >= 0) rows.add(new Row(Component.translatable("colonyboard.war.treasury", tr), req > 0
                ? Component.translatable(tr >= req ? "colonyboard.war.treasury.ok" : "colonyboard.war.treasury.low", req) : Component.empty(), null, req > 0 && tr < req, 0, -1, null));
            if (d.n("immunityH", 0) > 0) rows.add(new Row(Component.translatable("colonyboard.war.immunity", d.n("immunityH", 0)), Component.translatable("colonyboard.war.immunity.detail"), null, false, 0, -1, null));
            if (d.n("recoveryH", 0) > 0) rows.add(new Row(Component.translatable("colonyboard.war.recovery", d.n("recoveryH", 0)), Component.translatable("colonyboard.war.recovery.detail"), null, true, 0, -1, null));
            if (d.n("reparationsH", 0) > 0) rows.add(new Row(Component.translatable("colonyboard.war.reparations", d.n("reparationsH", 0)), Component.translatable("colonyboard.war.reparations.detail"), null, true, 0, -1, null));
            if (d.n("losses", 0) > 0) rows.add(new Row(Component.translatable("colonyboard.war.losses", d.n("losses", 0)), Component.empty(), null, false, 0, -1, null));
            if (st != null && st.colonyId() == colony.getID() && st.tax() < 0 && d.n("debtBlocksWar", 0) == 1)
                rows.add(new Row(Component.translatable("colonyboard.war.debtblock"), Component.translatable("colonyboard.war.debtblock.detail", -st.tax()), null, true, 0, -1, null));
            rows.add(new Row(Component.translatable("colonyboard.war.economy"), Component.translatable(d.n("autoBlocked", 0) == 1 ? "colonyboard.war.economy.auto.blocked" : "colonyboard.war.economy.open"),
                null, d.n("autoBlocked", 0) == 1, 0, -1, null));
        } else rows.add(stat("colonyboard.dash.noserver", "", false));
        if (!hurtRows.isEmpty()) {
            rows.add(new Row(Component.translatable("colonyboard.war.hurt.h", hurtRows.size()), Component.translatable("colonyboard.war.hurt.hint"), null, false, 0, -1, null));
            rows.addAll(hurtRows);
        }
        return rows;
    }

    private static Row stat(String key, String value, boolean bad) {
        return new Row(Component.translatable(key), Component.literal(value), null, bad, 1000, -1, null);
    }

    public static List<IBuildingView> buildings(IColonyView colony) {
        List<IBuildingView> list = new ArrayList<>();
        try { list.addAll(colony.getClientBuildingManager().getBuildings().values()); } catch (Throwable ignored) {}
        list.sort(Comparator.comparing(b -> buildingName(b).getString()));
        return list;
    }

    public static Component buildingName(IBuildingView b) {
        try {
            return Component.translatable(b.getBuildingDisplayName()).append(" " + b.getBuildingLevel());
        } catch (Throwable t) {
            BlockPos p = b.getID();
            return Component.literal(p.toShortString());
        }
    }

    private static boolean isBuilder(IBuildingView b) {
        try { return "builder".equals(b.getBuildingType().getRegistryName().getPath()); } catch (Throwable t) { return false; }
    }
}
