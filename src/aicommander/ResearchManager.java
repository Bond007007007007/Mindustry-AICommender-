package aicommander;

import arc.struct.Seq;
import arc.util.Log;
import arc.util.Time;
import mindustry.content.TechTree;
import mindustry.content.TechTree.TechNode;
import mindustry.game.EventType.ResearchEvent;
import mindustry.type.ItemStack;
import mindustry.world.blocks.storage.CoreBlock.CoreBuild;

import static mindustry.Vars.*;

/**
 * Autonomous campaign research manager.
 * In campaign mode, checks tech tree dependencies and spends core inventory items
 * to unlock new tiers of factories, turrets, and logistics.
 */
public class ResearchManager{
    private float lastCheckTime;

    public void update(){
        if(!state.isCampaign() || !state.isGame()) return;
        if(Time.time - lastCheckTime < 180f) return; // check every ~3 seconds
        lastCheckTime = Time.time;

        CoreBuild core = player.team().core();
        if(core == null) return;

        Seq<TechNode> nodes = TechTree.all;
        if(nodes == null || nodes.isEmpty()) return;

        for(TechNode node : nodes){
            if(node == null || node.content == null) continue;
            // Only examine locked nodes that have met objectives/parent requirements
            if(!node.content.unlocked() && isSelectable(node)){
                if(canAffordAndSpend(node, core)){
                    unlock(node);
                    Log.info("[AI Commander] Researched & unlocked: @", node.content.name);
                    break; // Unlock one per cycle to avoid core depletion
                }
            }
        }
    }

    private boolean isSelectable(TechNode node){
        if(node.objectives == null || node.objectives.isEmpty()) return true;
        for(int i = 0; i < node.objectives.size; i++){
            if(!node.objectives.get(i).complete()){
                return false;
            }
        }
        return true;
    }

    private boolean canAffordAndSpend(TechNode node, CoreBuild core){
        if(node.requirements == null || node.requirements.length == 0) return true;

        // Check if core contains all requirements
        for(ItemStack req : node.requirements){
            if(req == null || req.item == null) continue;
            int needed = req.amount;
            if(node.finishedRequirements != null){
                for(ItemStack fin : node.finishedRequirements){
                    if(fin != null && fin.item == req.item){
                        needed = Math.max(0, req.amount - fin.amount);
                        break;
                    }
                }
            }
            if(core.items.get(req.item) < needed){
                return false;
            }
        }

        // Deduct from core inventory and mark requirements
        for(int i = 0; i < node.requirements.length; i++){
            ItemStack req = node.requirements[i];
            if(req == null || req.item == null) continue;
            int needed = req.amount;
            ItemStack finished = (node.finishedRequirements != null && i < node.finishedRequirements.length) ? node.finishedRequirements[i] : null;
            if(finished != null){
                needed = Math.max(0, req.amount - finished.amount);
            }
            int take = Math.min(needed, core.items.get(req.item));
            core.items.remove(req.item, take);
            if(finished != null){
                finished.amount += take;
            }
        }
        return true;
    }

    private void unlock(TechNode node){
        node.content.unlock();
        TechNode parent = node.parent;
        while(parent != null){
            if(!parent.content.unlocked()){
                parent.content.unlock();
            }
            parent = parent.parent;
        }
        node.save();
        arc.Events.fire(new ResearchEvent(node.content));
    }
}
