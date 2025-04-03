package com.cathaybk.codingassistant.inspection;

import com.cathaybk.codingassistant.annotation.ApiMsgId;
import com.intellij.codeInspection.AbstractBaseJavaLocalInspectionTool;
import com.intellij.codeInspection.LocalQuickFix;
import com.intellij.codeInspection.ProblemDescriptor;
import com.intellij.codeInspection.ProblemsHolder;
import com.intellij.openapi.project.Project;
import com.intellij.psi.*;
import com.intellij.psi.util.PsiUtil;
import org.jetbrains.annotations.NotNull;

/**
 * 檢查Controller中的API方法是否有ApiMsgId註解
 */
public class ApiMsgIdInspection extends AbstractBaseJavaLocalInspectionTool {

    @NotNull
    @Override
    public PsiElementVisitor buildVisitor(@NotNull ProblemsHolder holder, boolean isOnTheFly) {
        return new JavaElementVisitor() {
            @Override
            public void visitMethod(PsiMethod method) {
                // 檢查方法是否在Controller類中
                PsiClass containingClass = method.getContainingClass();
                if (containingClass == null)
                    return;

                String className = containingClass.getName();
                if (className == null || !className.contains("Controller"))
                    return;

                // 檢查是否有Rest API註解
                boolean isApiMethod = false;
                for (PsiAnnotation annotation : method.getModifierList().getAnnotations()) {
                    String annoName = annotation.getQualifiedName();
                    if (annoName != null && (annoName.contains("RequestMapping") ||
                            annoName.contains("GetMapping") ||
                            annoName.contains("PostMapping") ||
                            annoName.contains("PutMapping") ||
                            annoName.contains("DeleteMapping"))) {
                        isApiMethod = true;
                        break;
                    }
                }

                if (!isApiMethod)
                    return;

                // 檢查是否有ApiMsgId註解
                PsiAnnotation apiMsgIdAnnotation = method.getAnnotation(ApiMsgId.class.getName());
                if (apiMsgIdAnnotation == null) {
                    holder.registerProblem(
                            method.getNameIdentifier() != null ? method.getNameIdentifier() : method,
                            "API方法缺少ApiMsgId註解",
                            new AddApiMsgIdQuickFix());
                }
            }
        };
    }

    /**
     * 快速修復：添加ApiMsgId註解
     */
    private static class AddApiMsgIdQuickFix implements LocalQuickFix {
        @NotNull
        @Override
        public String getName() {
            return "添加ApiMsgId註解";
        }

        @NotNull
        @Override
        public String getFamilyName() {
            return getName();
        }

        @Override
        public void applyFix(@NotNull Project project, @NotNull ProblemDescriptor descriptor) {
            PsiElement element = descriptor.getPsiElement();
            if (!(element instanceof PsiIdentifier))
                return;

            PsiMethod method = (PsiMethod) element.getParent();
            PsiElementFactory factory = JavaPsiFacade.getElementFactory(project);

            // 創建註解
            PsiAnnotation annotation = factory.createAnnotationFromText(
                    "@com.cathaybk.codingassistant.annotation.ApiMsgId(\"MSG_ID_HERE\")", method);
            method.getModifierList().addAfter(annotation, null);
        }
    }
}