package org.angularjs.codeInsight.router;

import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.Pair;
import com.intellij.openapi.util.text.StringUtil;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.*;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * @author Irina.Chernushina on 3/9/2016.
 */
public class AngularUiRouterGraphBuilder {
  @NotNull private final Project myProject;
  private final Map<String, UiRouterState> myStatesMap;
  private final Map<String, Template> myTemplatesMap;
  @Nullable private final RootTemplate myRootTemplate;
  private final VirtualFile myKey;

  public AngularUiRouterGraphBuilder(@NotNull Project project,
                                     @NotNull Map<String, UiRouterState> statesMap,
                                     @NotNull Map<String, Template> templatesMap,
                                     @Nullable RootTemplate rootTemplate, VirtualFile key) {
    myProject = project;
    myStatesMap = statesMap;
    myTemplatesMap = templatesMap;
    myRootTemplate = rootTemplate;
    myKey = key;
  }

  public VirtualFile getKey() {
    return myKey;
  }

  public GraphNodesBuilder createDataModel(AngularUiRouterDiagramProvider provider) {
    final GraphNodesBuilder nodesBuilder = new GraphNodesBuilder(myStatesMap, myTemplatesMap, myRootTemplate, myKey);
    nodesBuilder.build(provider, myProject);

    return nodesBuilder;
  }

  public static class GraphNodesBuilder {
    public static final String DEFAULT = "<default>";
    @NotNull private final Map<String, UiRouterState> myStatesMap;
    @NotNull private final Map<String, Template> myTemplatesMap;
    @Nullable private final RootTemplate myRootTemplate;
    private final VirtualFile myKey;

    private AngularUiRouterNode myRootNode;
    private final Map<String, AngularUiRouterNode> stateNodes = new HashMap<>();
    private final Map<String, AngularUiRouterNode> templateNodes = new HashMap<>();
    private final Map<Pair<String, String>, DiagramObject> templatePlaceHoldersNodes = new HashMap<>();
    private final Map<Pair<String, String>, AngularUiRouterNode> viewNodes = new HashMap<>();
    private final List<AngularUiRouterEdge> edges = new ArrayList<>();

    private final List<AngularUiRouterNode> allNodes = new ArrayList<>();

    public GraphNodesBuilder(@NotNull Map<String, UiRouterState> statesMap,
                             @NotNull Map<String, Template> templatesMap, @Nullable RootTemplate rootTemplate, VirtualFile key) {
      myStatesMap = statesMap;
      myTemplatesMap = templatesMap;
      myRootTemplate = rootTemplate;
      myKey = key;
    }

    public AngularUiRouterNode getRootNode() {
      return myRootNode;
    }

    public VirtualFile getKey() {
      return myKey;
    }

    public void build(@NotNull final AngularUiRouterDiagramProvider provider, @NotNull final Project project) {
      final DiagramObject rootDiagramObject;
      if (myRootTemplate != null) {
        myRootNode = getOrCreateTemplateNode(provider, normalizeTemplateUrl(myRootTemplate.getRelativeUrl()), myRootTemplate.getTemplate());
      } else {
        // todo remove from diagram if not used
        final PsiFile psiFile = PsiManager.getInstance(project).findFile(myKey);
        rootDiagramObject = new DiagramObject(Type.topLevelTemplate, "<unknown root template>", psiFile == null ? null :
                                              SmartPointerManager.getInstance(project).createSmartPsiElementPointer(psiFile));
        myRootNode = new AngularUiRouterNode(rootDiagramObject, provider);
      }

      setParentStates();
      for (Map.Entry<String, UiRouterState> entry : myStatesMap.entrySet()) {
        final UiRouterState state = entry.getValue();
        final DiagramObject stateObject = new DiagramObject(Type.state, state.getName(), state.getPointer());
        if (state.getPointer() == null) {
          stateObject.addError("Can not find the state definition");
        }
        final AngularUiRouterNode node = new AngularUiRouterNode(stateObject, provider);
        stateNodes.put(state.getName(), node);
        final String templateUrl = normalizeTemplateUrl(state.getTemplateUrl());

        if (templateUrl != null) {
          final AngularUiRouterNode templateNode = getOrCreateTemplateNode(provider, templateUrl, null);
          edges.add(new AngularUiRouterEdge(node, templateNode));

          if (state.hasViews()) {
            if (state.isAbstract()) {
              stateObject.addWarning("Abstract state can not be instantiated so it makes no sense to define views for it.");
            }
            else {
              stateObject.addWarning("Since 'views' are defined for state, template information would be ignored.");
            }
          }
        }
      }

      for (Map.Entry<String, UiRouterState> entry : myStatesMap.entrySet()) {
        final UiRouterState state = entry.getValue();
        final AngularUiRouterNode node = stateNodes.get(state.getName());
        assert node != null;

        final List<UiView> views = state.getViews();
        if (views != null && !views.isEmpty()) {
          for (UiView view : views) {
            final String name = StringUtil.isEmptyOrSpaces(view.getName()) ? DEFAULT : view.getName();
            final DiagramObject viewObject = new DiagramObject(Type.view, name, view.getPointer());
            final AngularUiRouterNode viewNode = new AngularUiRouterNode(viewObject, provider);
            viewNodes.put(Pair.create(state.getName(), name), viewNode);

            final String template = view.getTemplate();
            if (!StringUtil.isEmptyOrSpaces(template)) {
              final AngularUiRouterNode templateNode = getOrCreateTemplateNode(provider, template, null);
              edges.add(new AngularUiRouterEdge(viewNode, templateNode, "provides"));
            }
            edges.add(new AngularUiRouterEdge(node, viewNode));
          }
        }
      }

      // views can also refer to different states, so first all state nodes must be created
      for (Map.Entry<String, UiRouterState> entry : myStatesMap.entrySet()) {
        final UiRouterState state = entry.getValue();
        final AngularUiRouterNode node = stateNodes.get(state.getName());
        assert node != null;

        final List<UiView> views = state.getViews();
        if (views != null && !views.isEmpty()) {
          for (UiView view : views) {
            final String name = StringUtil.isEmptyOrSpaces(view.getName()) ? DEFAULT : view.getName();
            final AngularUiRouterNode viewNode = viewNodes.get(Pair.create(state.getName(), name));
            assert viewNode != null;

            final Pair<AngularUiRouterNode, String> pair = getParentTemplateNode(state.getName(), view.getName());
            if (pair != null && pair.getFirst() != null) {
              connectViewOrStateWithPlaceholder(viewNode, pair);
            }
          }
        } else {
          //find unnamed parent template for view
          final Pair<AngularUiRouterNode, String> pair = getParentTemplateNode(state.getName(), "");
          if (pair != null && pair.getFirst() != null) {
            connectViewOrStateWithPlaceholder(node, pair);
          }
        }
      }
      createStateParentEdges();

      allNodes.add(myRootNode);
      allNodes.addAll(stateNodes.values());
      allNodes.addAll(templateNodes.values());
      //allNodes.addAll(templatePlaceHoldersNodes.values());
      allNodes.addAll(viewNodes.values());
    }

    private void connectViewOrStateWithPlaceholder(AngularUiRouterNode viewNode, Pair<AngularUiRouterNode, String> pair) {
      final String placeholderName = pair.getSecond();
      //final String placeholderName = StringUtil.isEmptyOrSpaces(pair.getSecond()) ? DEFAULT : pair.getSecond();
      String usedTemplateUrl = null;

      final Type nodeType = pair.getFirst().getIdentifyingElement().getType();
      if (Type.template.equals(nodeType) || Type.topLevelTemplate.equals(nodeType)) {
        usedTemplateUrl = pair.getFirst().getIdentifyingElement().getName();
      } else if (Type.state.equals(nodeType)) {
        final String parentState = pair.getFirst().getIdentifyingElement().getName();
        final UiRouterState parentStateObject = myStatesMap.get(parentState);
        if (parentStateObject != null) {
          if (parentStateObject.hasViews()) {
            final List<UiView> parentViews = parentStateObject.getViews();
            for (UiView parentView : parentViews) {
              if (placeholderName.equals(parentView.getName())) {
                usedTemplateUrl = parentView.getTemplate();
                break;
              }
            }
          } else if (!StringUtil.isEmptyOrSpaces(parentStateObject.getTemplateUrl())) {
            usedTemplateUrl = parentStateObject.getTemplateUrl();
          }
        }
      }

      usedTemplateUrl = normalizeTemplateUrl(usedTemplateUrl);
      final DiagramObject placeholder = templatePlaceHoldersNodes.get(Pair.create(usedTemplateUrl, placeholderName));
      if (placeholder != null && placeholder.getParent() != null) {
        final AngularUiRouterEdge edge = new AngularUiRouterEdge(viewNode, placeholder.getParent(), "fills");
        edge.setTargetAnchor(placeholder);
        edges.add(edge);
      }
    }

    private void createStateParentEdges() {
      for (Map.Entry<String, AngularUiRouterNode> entry : stateNodes.entrySet()) {
        final String key = entry.getKey();
        final UiRouterState state = myStatesMap.get(key);
        if (state != null && state.getParentName() != null) {
          final AngularUiRouterNode parentState = stateNodes.get(state.getParentName());
          if (parentState != null) {
            edges.add(new AngularUiRouterEdge(entry.getValue(), parentState, "parent"));
          }
        }
      }
    }

    private void setParentStates() {
      for (Map.Entry<String, UiRouterState> entry : myStatesMap.entrySet()) {
        if (!StringUtil.isEmptyOrSpaces(entry.getValue().getParentName())) continue;
        final String key = entry.getKey();
        final int dotIdx = key.lastIndexOf('.');
        if (dotIdx > 0) {
          final String parentKey = key.substring(0, dotIdx);
          entry.getValue().setParentName(parentKey);
        }
      }
    }

    public List<AngularUiRouterEdge> getEdges() {
      return edges;
    }

    public List<AngularUiRouterNode> getAllNodes() {
      return allNodes;
    }

    @Nullable
    private Pair<AngularUiRouterNode, String> getParentTemplateNode(@NotNull final String state, @NotNull final String view) {
      final int idx = view.indexOf("@");
      if (idx < 0) {
        // parent or top level template
        if (state.contains(".")) {
          final UiRouterState routerState = myStatesMap.get(state);
          if (routerState == null) {
            return null;
          }
          return Pair.create(stateNodes.get(routerState.getParentName()), view);
        } else {
          return Pair.create(myRootNode, view);
        }
      } else {
        //absolute path
        //if (idx == 0) return Pair.create(myRootNode, view.substring(1));
        final String placeholderName = view.substring(0, idx);
        final String stateName = view.substring(idx + 1);
        if (StringUtil.isEmptyOrSpaces(stateName)) {
          return Pair.create(myRootNode, placeholderName);
        }
        return Pair.create(stateNodes.get(stateName), placeholderName);
      }
    }

    @NotNull
    private AngularUiRouterNode getOrCreateTemplateNode(AngularUiRouterDiagramProvider provider, @NotNull String templateUrl, @Nullable Template template) {
      template = template == null ? myTemplatesMap.get(templateUrl) : template;
      if (template == null) {
        // file not found
        final DiagramObject templateObject = new DiagramObject(Type.template, templateUrl, null);
        templateObject.addError("Can not find template file");
        templateNodes.put(templateUrl, new AngularUiRouterNode(templateObject, provider));
      }
      else if (!templateNodes.containsKey(templateUrl)) {
        final DiagramObject templateObject = new DiagramObject(Type.template, templateUrl, template.getPointer());
        final AngularUiRouterNode templateNode = new AngularUiRouterNode(templateObject, provider);
        templateNodes.put(templateUrl, templateNode);

        putPlaceholderNodes(provider, templateUrl, template, templateNode);
      }
      final AngularUiRouterNode templateNode = templateNodes.get(templateUrl);
      assert templateNode != null;
      return templateNode;
    }

    private void putPlaceholderNodes(AngularUiRouterDiagramProvider provider,
                                     @NotNull String templateUrl,
                                     Template template,
                                     AngularUiRouterNode templateNode) {
      final Map<String, SmartPsiElementPointer<PsiElement>> placeholders = template.getViewPlaceholders();
      if (placeholders != null) {
        for (Map.Entry<String, SmartPsiElementPointer<PsiElement>> pointerEntry : placeholders.entrySet()) {
          final String placeholder = pointerEntry.getKey();
          final DiagramObject placeholderObject = new DiagramObject(Type.templatePlaceholder,
                                                                    StringUtil.isEmptyOrSpaces(placeholder) ? DEFAULT : placeholder,
                                                                    pointerEntry.getValue());
          //final MyNode placeholderNode = new MyNode(placeholderObject, provider);
          templateNode.getIdentifyingElement().addChild(placeholderObject, templateNode);
          templatePlaceHoldersNodes.put(Pair.create(templateUrl, placeholder), placeholderObject);
//          final MyEdge edge = new MyEdge(templateNode, placeholderNode);
//          edges.add(edge);
        }
      }
    }
  }

  public static String normalizeTemplateUrl(@Nullable String url) {
    if (url == null) return null;
    url = url.startsWith("/") ? url.substring(1) : url;
    url = url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    return url;
  }
}
