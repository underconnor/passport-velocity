package io.github.underconnor.passport.velocity;

import com.mojang.brigadier.tree.*;
import com.velocitypowered.api.command.CommandSource;
import io.github.underconnor.passport.core.*;
import java.util.*;
import java.util.function.Predicate;

/** Filter the outgoing packet only; backend nodes retain the backend's already-applied permissions. */
final class CommandTreeFilter {
    private CommandTreeFilter() {}
    static void filter(RootCommandNode<?> root,CommandSource source,boolean inspect,
                       Predicate<String> proxyCommand,Predicate<String> proxyAllowed) {
        for(CommandNode<?> node:List.copyOf(root.getChildren())) {
            String name=node.getName();
            boolean owned=proxyCommand.test(name);
            if(CommandSelection.blockedBuiltin(name) || !inspect && CommandPresentation.informationRoot(name)
                    || owned && !proxyAllowed.test(name)) {
                root.removeChildByName(name); continue;
            }
            if(owned) filterProxyChildren(node,source);
            if(!inspect && CommandPresentation.velocityRoot(name)) {
                for(CommandNode<?> child:List.copyOf(node.getChildren()))
                    if(!child.getName().equals("reload")) node.removeChildByName(child.getName());
                if(node.getChildren().isEmpty()) root.removeChildByName(name);
            }
        }
    }
    @SuppressWarnings("unchecked") private static void filterProxyChildren(CommandNode<?> parent,CommandSource source) {
        for(CommandNode<?> child:List.copyOf(parent.getChildren())) {
            boolean allowed;
            try { allowed=((CommandNode<CommandSource>)child).canUse(source); }
            catch(RuntimeException invalidRequirement) { allowed=false; }
            if(!allowed) parent.removeChildByName(child.getName());
            else filterProxyChildren(child,source);
        }
    }
}
