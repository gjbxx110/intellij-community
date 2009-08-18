package org.jetbrains.idea.maven.dom.references;

import com.intellij.codeInsight.lookup.LookupElementFactory;
import com.intellij.codeInsight.lookup.MutableLookupElement;
import com.intellij.codeInspection.LocalQuickFix;
import com.intellij.codeInspection.LocalQuickFixProvider;
import com.intellij.codeInspection.ProblemDescriptor;
import com.intellij.notification.NotificationListener;
import com.intellij.notification.NotificationType;
import com.intellij.notification.impl.NotificationsManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.TextRange;
import com.intellij.openapi.util.io.FileUtil;
import com.intellij.openapi.vfs.VfsUtil;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiManager;
import com.intellij.util.PathUtil;
import com.intellij.util.xml.DomFileElement;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.idea.maven.dom.MavenDomBundle;
import org.jetbrains.idea.maven.dom.MavenDomUtil;
import org.jetbrains.idea.maven.dom.model.MavenDomProjectModel;
import org.jetbrains.idea.maven.project.MavenId;
import org.jetbrains.idea.maven.utils.MavenConstants;
import org.jetbrains.idea.maven.utils.MavenUtil;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

public class MavenModulePsiReference extends MavenPsiReference implements LocalQuickFixProvider {
  public MavenModulePsiReference(PsiElement element, String text, TextRange range) {
    super(element, text, range);
  }

  public PsiElement resolve() {
    VirtualFile baseDir = myPsiFile.getVirtualFile().getParent();
    String relPath = FileUtil.toSystemIndependentName(myText + "/" + MavenConstants.POM_XML);
    VirtualFile file = baseDir.findFileByRelativePath(relPath);

    if (file == null) return null;

    return getPsiFile(file);
  }

  public Object[] getVariants() {
    List<DomFileElement<MavenDomProjectModel>> files = MavenDomUtil.collectProjectPoms(getProject());

    List<Object> result = new ArrayList<Object>();

    for (DomFileElement<MavenDomProjectModel> eachDomFile : files) {
      VirtualFile eachVFile = eachDomFile.getOriginalFile().getVirtualFile();
      if (eachVFile == myVirtualFile) continue;

      PsiFile psiFile = eachDomFile.getFile();
      String modulePath = calcRelativeModulePath(myVirtualFile, eachVFile);

      MutableLookupElement<PsiFile> lookup = LookupElementFactory.getInstance().createLookupElement(psiFile, modulePath);
      lookup.setPresentableText(modulePath);
      result.add(lookup);
    }

    return result.toArray();
  }

  public static String calcRelativeModulePath(VirtualFile parentPom, VirtualFile modulePom) {
    String result = MavenDomUtil.calcRelativePath(parentPom.getParent(), modulePom);
    int to = result.length() - ("/" + MavenConstants.POM_XML).length();
    if (to < 0) {
      // todo IDEADEV-35440
      throw new RuntimeException("Filed to calculate relative path for:" +
                                 "\nparentPom: " + parentPom + "(valid: " + parentPom.isValid() + ")" +
                                 "\nmodulePom: " + modulePom + "(valid: " + modulePom.isValid() + ")" +
                                 "\nequals:" + parentPom.equals(modulePom));
    }
    return result.substring(0, to);
  }

  private PsiFile getPsiFile(VirtualFile file) {
    return PsiManager.getInstance(getProject()).findFile(file);
  }

  private Project getProject() {
    return myPsiFile.getProject();
  }

  public LocalQuickFix[] getQuickFixes() {
    if (myText.length() == 0 || resolve() != null) return LocalQuickFix.EMPTY_ARRAY;
    return new LocalQuickFix[]{new CreateModulePomFix()};
  }

  private class CreateModulePomFix implements LocalQuickFix {
    @NotNull
    public String getName() {
      return MavenDomBundle.message("fix.create.module.pom");
    }

    @NotNull
    public String getFamilyName() {
      return MavenDomBundle.message("inspection.group");
    }

    public void applyFix(@NotNull Project project, @NotNull ProblemDescriptor d) {
      try {
        VirtualFile modulePom = createModulePom();
        MavenId id = MavenDomUtil.describe(myPsiFile);

        String groupId = id.getGroupId() == null ? "groupId" : id.getGroupId();
        String artifactId = modulePom.getParent().getName();
        String version = id.getVersion() == null ? "version" : id.getVersion();
        MavenUtil.runMavenProjectFileTemplate(project, modulePom, new MavenId(groupId, artifactId, version), true);
      }
      catch (IOException e) {
        NotificationsManager.getNotificationsManager()
          .notify("Cannot create a module", e.getMessage(), NotificationType.ERROR, NotificationListener.REMOVE);
      }
    }

    private VirtualFile createModulePom() throws IOException {
      VirtualFile baseDir = myVirtualFile.getParent();
      String modulePath = PathUtil.getCanonicalPath(baseDir.getPath() + "/" + myText);
      VirtualFile moduleDir = VfsUtil.createDirectories(modulePath);
      return moduleDir.createChildData(this, MavenConstants.POM_XML);
    }
  }
}
