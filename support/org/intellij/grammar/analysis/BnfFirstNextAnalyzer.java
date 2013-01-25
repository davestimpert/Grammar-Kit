/*
 * Copyright 2011-2011 Gregory Shrago
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.intellij.grammar.analysis;

import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.util.Comparing;
import com.intellij.openapi.util.text.StringUtil;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiReference;
import com.intellij.psi.search.searches.ReferencesSearch;
import com.intellij.psi.tree.IElementType;
import com.intellij.psi.util.PsiTreeUtil;
import com.intellij.util.CommonProcessors;
import com.intellij.util.containers.ContainerUtil;
import gnu.trove.THashMap;
import gnu.trove.THashSet;
import gnu.trove.TObjectHashingStrategy;
import org.intellij.grammar.KnownAttribute;
import org.intellij.grammar.generator.ParserGeneratorUtil;
import org.intellij.grammar.psi.*;
import org.intellij.grammar.psi.impl.GrammarUtil;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.*;

/**
 * @author gregsh
 */
public class BnfFirstNextAnalyzer {
  
  private static final Logger LOG = Logger.getInstance("org.intellij.grammar.analysis.BnfFirstNextAnalyzer"); 
  
  public static final String MATCHES_EOF = "-eof-";
  public static final String MATCHES_NOTHING = "-never-matches-";
  public static final String MATCHES_ANY = "-any-";

  public static final BnfExpression BNF_MATCHES_EOF     = ParserGeneratorUtil.createFake(MATCHES_EOF);
  public static final BnfExpression BNF_MATCHES_NOTHING = ParserGeneratorUtil.createFake(MATCHES_NOTHING);
  public static final BnfExpression BNF_MATCHES_ANY     = ParserGeneratorUtil.createFake(MATCHES_ANY);

  private boolean myBackward;
  private boolean myPublicRuleOpaque;

  public BnfFirstNextAnalyzer setBackward(boolean backward) {
    myBackward = backward;
    return this;
  }

  public BnfFirstNextAnalyzer setPublicRuleOpaque(boolean publicRuleOpaque) {
    myPublicRuleOpaque = publicRuleOpaque;
    return this;
  }

  public Set<BnfExpression> calcFirst(@NotNull BnfRule rule) {
    Set<BnfRule> visited = new THashSet<BnfRule>();
    visited.add(rule);
    return calcFirstInner(rule.getExpression(), new THashSet<BnfExpression>(), visited);
  }

  public Map<BnfExpression, BnfExpression> calcNext(@NotNull BnfRule targetRule) {
    return calcNextInner(targetRule.getExpression(), new THashMap<BnfExpression, BnfExpression>(), new THashSet<BnfRule>());
  }
  
  private Map<BnfExpression, BnfExpression> calcNextInner(@NotNull BnfExpression targetExpression, Map<BnfExpression, BnfExpression> result, Set<BnfRule> visited) {
    LinkedList<BnfExpression> stack = new LinkedList<BnfExpression>();
    THashSet<BnfRule> totalVisited = new THashSet<BnfRule>();
    Set<BnfExpression> curResult = new THashSet<BnfExpression>();
    stack.add(targetExpression);
    main: while (!stack.isEmpty()) {

      PsiElement cur = stack.removeLast();
      BnfExpression startingExpr = cur instanceof BnfReferenceOrToken? (BnfExpression)cur : null;
      PsiElement parent = cur.getParent();
      while (parent instanceof BnfExpression) {
        curResult.clear();
        PsiElement grandPa = parent.getParent();
        if (grandPa instanceof BnfRule && ParserGeneratorUtil.Rule.isExternal((BnfRule)grandPa) ||
            grandPa instanceof BnfExternalExpression /*todo support meta rules*/) {
          result.put(BNF_MATCHES_ANY, startingExpr);
          break;
        }
        else if (parent instanceof BnfSequence) {
          List<BnfExpression> children  = ((BnfSequence)parent).getExpressionList();
          int idx = children.indexOf(cur);
          List<BnfExpression> sublist = myBackward? children.subList(0, idx) : children.subList(idx + 1, children.size());
          calcSequenceFirstInner(sublist, curResult, visited);
          boolean skipResolve = !curResult.contains(BNF_MATCHES_EOF);
          for (BnfExpression e : curResult) {
            result.put(e, startingExpr);
          }
          if (skipResolve) continue main;
        }
        else if (parent instanceof BnfQuantified) {
          IElementType effectiveType = ParserGeneratorUtil.getEffectiveType(parent);
          if (effectiveType == BnfTypes.BNF_OP_ZEROMORE || effectiveType == BnfTypes.BNF_OP_ONEMORE) {
            calcFirstInner((BnfExpression)parent, curResult, visited);
            for (BnfExpression e : curResult) {
              result.put(e, startingExpr);
            }
          }
        }
        cur = parent;
        parent = grandPa;
      }
      if (parent instanceof BnfRule && totalVisited.add((BnfRule)parent)) {
        BnfRule rule = (BnfRule)parent;
        for (PsiReference reference : ReferencesSearch.search(rule, rule.getUseScope()).findAll()) {
          PsiElement element = reference.getElement();
          if (element instanceof BnfExpression && PsiTreeUtil.getParentOfType(element, BnfPredicate.class) == null) {
            BnfAttr attr = PsiTreeUtil.getParentOfType(element, BnfAttr.class);
            if (attr != null) {
              if (KnownAttribute.RECOVER_UNTIL.getName().equals(attr.getName())) {
                result.put(BNF_MATCHES_ANY, startingExpr);
              }
            }
            else {
              stack.add((BnfExpression)element);
            }
          }
        }
      }
    }
    if (result.isEmpty()) result.put(BNF_MATCHES_EOF, null);
    return result;
  }

  private Set<BnfExpression> calcSequenceFirstInner(List<BnfExpression> expressions, final Set<BnfExpression> result, final Set<BnfRule> visited) {
    boolean matchesEof = !result.add(BNF_MATCHES_EOF);

    Set<BnfExpression> pinned;
    if (!myBackward) {
      BnfExpression firstItem = ContainerUtil.getFirstItem(expressions);
      if (firstItem == null) return result;
      BnfRule rule = ParserGeneratorUtil.Rule.of(firstItem);
      pinned = new HashSet<BnfExpression>();
      GrammarUtil.processPinnedExpressions(rule, new CommonProcessors.CollectProcessor<BnfExpression>(pinned));
    }
    else pinned = Collections.emptySet();

    boolean pinApplied = false;
    List<BnfExpression> list = myBackward ? ContainerUtil.reverse(expressions) : expressions;
    for (int i = 0, size = list.size(); i < size; i++) {
      if (!result.remove(BNF_MATCHES_EOF)) break;
      matchesEof |= pinApplied;
      BnfExpression e = list.get(i);
      calcFirstInner(e, result, visited, i < size - 1? list.subList(i + 1, size) : null);
      pinApplied |= pinned.contains(e);
    }
    // add empty back if was there before
    if (matchesEof) result.add(BNF_MATCHES_EOF);
    return result;
  }

  public Set<BnfExpression> calcFirstInner(BnfExpression expression, Set<BnfExpression> result, Set<BnfRule> visited) {
    return calcFirstInner(expression, result, visited, null);
  }

  public Set<BnfExpression> calcFirstInner(BnfExpression expression, Set<BnfExpression> result, Set<BnfRule> visited, @Nullable List<BnfExpression> forcedNext) {
    BnfFile file = (BnfFile)expression.getContainingFile();
    if (expression instanceof BnfLiteralExpression) {
      result.add(expression);
    }
    else if (expression instanceof BnfReferenceOrToken) {
      BnfRule rule = file.getRule(expression.getText());
      if (rule != null) {
        if (ParserGeneratorUtil.Rule.isExternal(rule)) {
          BnfExpression callExpr = ContainerUtil.getFirstItem(GrammarUtil.getExternalRuleExpressions(rule));
          if (callExpr instanceof BnfReferenceOrToken && file.getRule(callExpr.getText()) == null) {
            result.add(callExpr);
            return result;
          }
        }
        if (myPublicRuleOpaque && !ParserGeneratorUtil.Rule.isPrivate(rule) ||
            !visited.add(rule)) {
          if (!(ParserGeneratorUtil.Rule.firstNotTrivial(rule) instanceof BnfPredicate)) {
            result.add(expression);
          }
        }
        else {
          calcFirstInner(rule.getExpression(), result, visited, forcedNext);
          boolean removed = visited.remove(rule);
          LOG.assertTrue(removed, "path corruption detected");
        }
      }
      else {
        result.add(expression);
      }
    }
    else if (expression instanceof BnfParenthesized) {
      calcFirstInner(((BnfParenthesized)expression).getExpression(), result, visited, forcedNext);
      if (expression instanceof BnfParenOptExpression) {
        result.add(BNF_MATCHES_EOF);
      }
    }
    else if (expression instanceof BnfChoice) {
      boolean matchesNothing = result.remove(BNF_MATCHES_NOTHING);
      boolean matchesSomething = false;
      for (BnfExpression child : ((BnfChoice)expression).getExpressionList()) {
        calcFirstInner(child, result, visited, forcedNext);
        matchesSomething |= !result.remove(BNF_MATCHES_NOTHING);
      }
      if (!matchesSomething || matchesNothing) result.add(BNF_MATCHES_NOTHING);
    }
    else if (expression instanceof BnfSequence) {
      calcSequenceFirstInner(((BnfSequence)expression).getExpressionList(), result, visited);
    }
    else if (expression instanceof BnfQuantified) {
      calcFirstInner(((BnfQuantified)expression).getExpression(), result, visited, forcedNext);
      IElementType effectiveType = ParserGeneratorUtil.getEffectiveType(expression);
      if (effectiveType == BnfTypes.BNF_OP_OPT || effectiveType == BnfTypes.BNF_OP_ZEROMORE) {
        result.add(BNF_MATCHES_EOF);
      }
    }
    else if (expression instanceof BnfExternalExpression) {
      List<BnfExpression> expressionList = ((BnfExternalExpression)expression).getExpressionList();
      if (expressionList.size() == 1 && ParserGeneratorUtil.Rule.isMeta(ParserGeneratorUtil.Rule.of(expression))) {
        result.add(expression);
      }
      else {
        BnfExpression ruleRef = expressionList.get(0);
        Set<BnfExpression> metaResults = calcFirstInner(ruleRef, new LinkedHashSet<BnfExpression>(), visited, forcedNext);
        List<String> params = null;
        for (BnfExpression e : metaResults) {
          if (e instanceof BnfExternalExpression) {
            if (params == null) {
              BnfRule metaRule = (BnfRule)ruleRef.getReference().resolve();
              if (metaRule == null) {
                LOG.error("ruleRef:" + ruleRef.getText() +", metaResult:" + metaResults);
                continue;
              }
              params = GrammarUtil.collectExtraArguments(metaRule, metaRule.getExpression());
            }
            int idx = params.indexOf(e.getText());
            if (idx > -1 && idx + 1 < expressionList.size()) {
              calcFirstInner(expressionList.get(idx + 1), result, visited, null);
            }
          }
          else {
            result.add(e);
          }
        }
      }
    }
    else if (myBackward && expression instanceof BnfPredicate) {
      result.add(BNF_MATCHES_EOF);
    }
    else if (expression instanceof BnfPredicate) {
      IElementType elementType = ((BnfPredicate)expression).getPredicateSign().getFirstChild().getNode().getElementType();
      BnfExpression predicateExpression = ((BnfPredicate)expression).getExpression();
      // take only one token into account which is not exactly correct but better than nothing
      Set<BnfExpression> conditions = calcFirstInner(predicateExpression, new THashSet<BnfExpression>(new TextStrategy()), visited, null);
      Set<BnfExpression> next = forcedNext == null? calcNextInner(expression, new THashMap<BnfExpression, BnfExpression>(), visited).keySet() :
        calcSequenceFirstInner(forcedNext, new THashSet<BnfExpression>(new TextStrategy()), visited);
      Set<BnfExpression> mixed = new THashSet<BnfExpression>(new TextStrategy());
      if (predicateExpression instanceof BnfParenExpression) predicateExpression = ((BnfParenExpression)predicateExpression).getExpression();
      boolean skip = predicateExpression instanceof BnfSequence && ((BnfSequence)predicateExpression).getExpressionList().size() > 1; // todo calc min length ?
      if (!skip) {
        // skip external methods
        for (Iterator<BnfExpression> iterator = next.iterator(); !skip && iterator.hasNext(); ) {
          skip = GrammarUtil.isExternalReference(iterator.next());
        }
        for (Iterator<BnfExpression> iterator = conditions.iterator(); !skip && iterator.hasNext(); ) {
          skip = GrammarUtil.isExternalReference(iterator.next());
        }
      }
      if (skip) {
        mixed.addAll(next);
        mixed.remove(BNF_MATCHES_EOF);
      }
      else if (elementType == BnfTypes.BNF_OP_AND) {
        if (!conditions.contains(BNF_MATCHES_EOF)) {
          if (next.contains(BNF_MATCHES_ANY)) {
            mixed.addAll(conditions);
          }
          else {
            mixed.addAll(next);
            mixed.retainAll(conditions);
            if (mixed.isEmpty()) {
              mixed.add(BNF_MATCHES_NOTHING);
            }
          }
        }
        else {
          mixed.addAll(next);
        }
      }
      else {
        if (!conditions.contains(BNF_MATCHES_EOF)) {
          mixed.addAll(next);
          mixed.removeAll(conditions);
          if (mixed.isEmpty()) {
            mixed.add(BNF_MATCHES_NOTHING);
          }
        }
        else {
          mixed.add(BNF_MATCHES_NOTHING);
        }
      }
      result.addAll(mixed);
    }

    return result;
  }

  public Set<String> asStrings(Set<BnfExpression> expressions) {
    Set<String> result = new TreeSet<String>();
    for (BnfExpression expression : expressions) {
      if (expression instanceof BnfLiteralExpression) {
        String text = expression.getText();
        result.add(StringUtil.isQuotedString(text) ? '\'' + StringUtil.unquoteString(text) + '\'' : text);
      }
      else if (GrammarUtil.isExternalReference(expression)) {
        result.add("#" + expression.getText());
      }
      else {
        result.add(expression.getText());
      }
    }
    return result;
  }

  private static class TextStrategy implements TObjectHashingStrategy<BnfExpression> {
    @Override
    public int computeHashCode(BnfExpression e) {
      return e.getText().hashCode();
    }

    @Override
    public boolean equals(BnfExpression e1, BnfExpression e2) {
      return Comparing.equal(e1.getText(), e2.getText());
    }
  }
}
