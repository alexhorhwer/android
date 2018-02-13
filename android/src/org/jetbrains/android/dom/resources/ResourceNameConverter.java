package org.jetbrains.android.dom.resources;

import com.android.SdkConstants;
import com.android.ide.common.resources.ValueResourceNameValidator;
import com.android.resources.ResourceType;
import com.intellij.codeInspection.LocalQuickFix;
import com.intellij.codeInspection.LocalQuickFixProvider;
import com.intellij.openapi.module.Module;
import com.intellij.openapi.util.TextRange;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiReference;
import com.intellij.psi.xml.XmlTag;
import com.intellij.util.xml.*;
import org.jetbrains.android.dom.converters.AndroidResourceReferenceBase;
import org.jetbrains.android.facet.AndroidFacet;
import org.jetbrains.android.inspections.CreateValueResourceQuickFix;
import org.jetbrains.android.resourceManagers.LocalResourceManager;
import org.jetbrains.android.resourceManagers.ModuleResourceManagers;
import org.jetbrains.android.resourceManagers.ValueResourceInfoImpl;
import org.jetbrains.annotations.NonNls;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;

/**
 * @author Eugene.Kudelevsky
 */
public class ResourceNameConverter extends ResolvingConverter<String> implements CustomReferenceConverter<String> {
  @Override
  public String fromString(@Nullable @NonNls String s, ConvertContext context) {
    if (getErrorMessage(s, context) != null) {
      return null;
    }
    return s;
  }

  @Override
  public String toString(@Nullable String s, ConvertContext context) {
    return s;
  }

  @Override
  public String getErrorMessage(@Nullable String s, ConvertContext context) {
    ResourceType type = null;
    XmlTag tag = context.getTag();
    if (tag != null && SdkConstants.TAG_ATTR.equals(tag.getName())) {
      // The resource name validator needs to know if we're dealing with
      // an <attr>
      type = ResourceType.ATTR;
    }
    return s == null ? null : ValueResourceNameValidator.getErrorText(s, type);
  }

  @NotNull
  @Override
  public Collection<String> getVariants(ConvertContext context) {
    final DomElement element = context.getInvocationElement();

    if (!(element instanceof GenericAttributeValue)) {
      return Collections.emptyList();
    }
    if (element.getParent() instanceof Style) {
      return getStyleNameVariants(context, (GenericAttributeValue)element);
    }
    return Collections.emptyList();
  }

  private static Collection<String> getStyleNameVariants(ConvertContext context, GenericAttributeValue element) {
    final Module module = context.getModule();

    if (module == null) {
      return Collections.emptyList();
    }
    final LocalResourceManager manager = LocalResourceManager.getInstance(module);

    if (manager == null) {
      return Collections.emptyList();
    }
    final Collection<String> styleNames = manager.getResourceNames(ResourceType.STYLE);
    final List<String> result = new ArrayList<>();

    final String currentValue = element.getStringValue();
    for (String name : styleNames) {
      if (currentValue == null || !currentValue.startsWith(name)) {
        result.add(name + '.');
      }
    }
    return result;
  }

  @NotNull
  @Override
  public PsiReference[] createReferences(GenericDomValue<String> value, PsiElement element, ConvertContext context) {
    final Module module = context.getModule();

    if (module == null) {
      return PsiReference.EMPTY_ARRAY;
    }
    AndroidFacet facet = AndroidFacet.getInstance(module);

    if (facet == null) {
      return PsiReference.EMPTY_ARRAY;
    }
    final DomElement parent = value.getParent();

    if (parent instanceof Style) {
      return getReferencesInStyleName((Style)parent, value, facet);
    }
    return PsiReference.EMPTY_ARRAY;
  }

  private static PsiReference[] getReferencesInStyleName(@NotNull Style style,
                                                         @NotNull GenericDomValue<String> value,
                                                         @NotNull AndroidFacet facet) {
    final String s = value.getStringValue();

    if (s == null) {
      return PsiReference.EMPTY_ARRAY;
    }
    final String[] ids = s.split("\\.");
    if (ids.length < 2 ||
        style.getParentStyle().getStringValue() != null) {
      return PsiReference.EMPTY_ARRAY;
    }
    final List<PsiReference> result = new ArrayList<>(ids.length - 1);
    int offset = s.length();

    for (int i = ids.length - 1; i >= 0; i--) {
      if (i < ids.length - 1) {
        final String parentStyleName = s.substring(0, offset);
        final ResourceValue val = ResourceValue.referenceTo((char)0, null, ResourceType.STYLE.getName(), parentStyleName);
        result.add(new MyParentStyleReference(value, new TextRange(1, 1 + offset), val, facet));

        if (hasExplicitParent(facet, parentStyleName)) {
          break;
        }
      }
      offset = offset - ids[i].length() - 1;
    }
    return result.toArray(new PsiReference[result.size()]);
  }

  public static boolean hasExplicitParent(@NotNull AndroidFacet facet, @NotNull String localStyleName) {
    LocalResourceManager resourceManager = ModuleResourceManagers.getInstance(facet).getLocalResourceManager();
    List<ValueResourceInfoImpl> styles = resourceManager.findValueResourceInfos(ResourceType.STYLE.getName(), localStyleName, true, false);

    if (styles.isEmpty()) {
      return false;
    }
    // all resolved styles have explicit parents
    for (ValueResourceInfoImpl info : styles) {
      final ResourceElement domElement = info.computeDomElement();

      if (!(domElement instanceof Style) || ((Style)domElement).getParentStyle().getStringValue() == null) {
        return false;
      }
    }
    return true;
  }

  public static class MyParentStyleReference extends AndroidResourceReferenceBase implements LocalQuickFixProvider {

    public MyParentStyleReference(@NotNull GenericDomValue value,
                                  @Nullable TextRange range,
                                  @NotNull ResourceValue resourceValue,
                                  @NotNull AndroidFacet facet) {
      super(value, range, resourceValue, facet);
    }

    @Override
    public LocalQuickFix[] getQuickFixes() {
      final String resourceName = getValue();

      if (resourceName.isEmpty()) {
        return LocalQuickFix.EMPTY_ARRAY;
      }
      final PsiFile psiFile = getElement().getContainingFile();
      if (psiFile == null) {
        return LocalQuickFix.EMPTY_ARRAY;
      }
      return new LocalQuickFix[]{new CreateValueResourceQuickFix(myFacet, ResourceType.STYLE, resourceName, psiFile, false)};
    }
  }
}
