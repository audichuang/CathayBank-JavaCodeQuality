package com.cathaybk.codingassistant.inspection;

import com.intellij.codeInspection.*;
import com.intellij.openapi.project.Project;
import com.intellij.psi.*;
import com.intellij.psi.javadoc.PsiDocComment;
import com.intellij.util.IncorrectOperationException;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * 檢查 Controller 中的 API 方法是否有正確的 電文代號 註解
 */
public class ApiMsgIdInspection extends AbstractBaseJavaLocalInspectionTool {

    @NotNull
    @Override
    public String getShortName() {
        return "ApiMsgIdInspection";
    }

    @NotNull
    @Override
    public String getDisplayName() {
        return "API MsgID檢查";
    }

    @NotNull
    @Override
    public String getGroupDisplayName() {
        return "CathayBk規範檢查";
    }

    @Override
    public ProblemDescriptor @Nullable [] checkMethod(@NotNull PsiMethod method, @NotNull InspectionManager manager,
            boolean isOnTheFly) {
        // 檢查是否是Controller方法
        if (!isControllerMethod(method)) {
            return null;
        }

        // 檢查方法是否有Javadoc註解
        PsiDocComment docComment = method.getDocComment();
        if (docComment == null) {
            // 沒有Javadoc註解，需要提示
            return new ProblemDescriptor[] {
                    createProblemDescriptor(method, manager, isOnTheFly)
            };
        }

        // 檢查Javadoc註解中是否包含正確格式的電文代號
        String docText = docComment.getText();

        // 使用更寬鬆的正則表達式檢查格式：前面是電文代號格式，後面跟隨空格和說明文字
        // 允許前段由字母、數字和連字符組成，不強制特定格式
        if (!docText.matches("(?s).*\\b[A-Za-z0-9]+-[A-Za-z0-9]+-[A-Za-z0-9]+\\s+.+.*")) {
            // 找不到符合格式的電文代號
            return new ProblemDescriptor[] {
                    createProblemDescriptor(method, manager, isOnTheFly)
            };
        }

        return null;
    }

    /**
     * 判斷一個方法是否是Controller方法
     */
    private boolean isControllerMethod(PsiMethod method) {
        // 檢查方法是否有@PostMapping、@GetMapping等註解
        PsiAnnotation[] annotations = method.getModifierList().getAnnotations();
        for (PsiAnnotation annotation : annotations) {
            String qualifiedName = annotation.getQualifiedName();
            if (qualifiedName != null && (qualifiedName.endsWith("Mapping") ||
                    qualifiedName.contains("RequestMapping") ||
                    qualifiedName.contains("PostMapping") ||
                    qualifiedName.contains("GetMapping"))) {
                return true;
            }
        }
        return false;
    }

    /**
     * 建立問題描述
     */
    private ProblemDescriptor createProblemDescriptor(PsiMethod method, InspectionManager manager, boolean isOnTheFly) {
        return manager.createProblemDescriptor(
                method.getNameIdentifier(),
                "API方法缺少正確的電文代號註解，格式應為: XXX-X-XXXX 說明文字",
                new AddApiIdDocFix(),
                ProblemHighlightType.WARNING,
                isOnTheFly);
    }

    /**
     * 提供快速修復功能
     */
    private static class AddApiIdDocFix implements LocalQuickFix {
        @NotNull
        @Override
        public String getName() {
            return "添加電文代號註解";
        }

        @NotNull
        @Override
        public String getFamilyName() {
            return getName();
        }

        @Override
        public void applyFix(@NotNull Project project, @NotNull ProblemDescriptor descriptor) {
            try {
                PsiElement element = descriptor.getPsiElement();
                if (element == null)
                    return;

                PsiMethod method = (PsiMethod) element.getParent();

                // 創建一個新的Javadoc註解
                PsiElementFactory factory = JavaPsiFacade.getElementFactory(project);
                String apiId = generateApiId(method);
                PsiDocComment newDocComment = factory.createDocCommentFromText(
                        "/**\n * " + apiId + " [請填寫API描述]\n */");

                // 如果已有註解則替換，否則新增
                PsiDocComment existingComment = method.getDocComment();
                if (existingComment != null) {
                    existingComment.replace(newDocComment);
                } else {
                    method.addBefore(newDocComment, method.getModifierList());
                }
            } catch (IncorrectOperationException e) {
                // 忽略異常
            }
        }

        /**
         * 為方法生成一個電文代號模板
         */
        private String generateApiId(PsiMethod method) {
            String className = "";
            PsiClass containingClass = method.getContainingClass();
            if (containingClass != null) {
                className = containingClass.getName();
                if (className != null) {
                    className = className.toUpperCase().replace("CONTROLLER", "");
                }
            }

            String methodName = method.getName().toUpperCase();
            return "XXX-X-" + (className != null ? className : "") + "_" + methodName;
        }
    }
}