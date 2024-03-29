package co.marcin.darkrise.riseresources.requirements;

import com.sucy.skill.SkillAPI;
import com.sucy.skill.api.skills.Skill;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

public class SkillAPISkillRequirement extends Requirement {
    public static final String NAME = "SKILLAPI_skill";

    private final String skill;
    private final int    level;

    public SkillAPISkillRequirement(String fullString) {
        super(fullString);
        String[] split = fullString.split(":");
        if (split.length == 2) {
            this.level = 1;
        } else if (split.length == 3) {
            this.level = Math.max(1, Integer.parseInt(split[2]));
        } else throw new IllegalArgumentException();
        Skill skill = SkillAPI.getSkill(split[1]);
        if (skill == null) throw new IllegalArgumentException("Unknown skill \""+split[1]+'\"');
        this.skill = skill.getKey();
    }

    @Override
    @NotNull
    public String getName() {
        return NAME;
    }

    @Override
    public boolean meets(@NotNull Player player) {
        return SkillAPI.getPlayerData(player).getSkillLevel(this.skill) >= this.level;
    }
}
