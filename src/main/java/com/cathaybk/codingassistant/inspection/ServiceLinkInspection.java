package com.cathaybk.codingassistant.inspection;

import com.cathaybk.codingassistant.annotation.ApiMsgId;
import com.intellij.codeInspection.AbstractBaseJavaLocalInspectionTool;
import com.intellij.codeInspection.LocalQuickFix;
import com.intellij.codeInspection.ProblemDescriptor;
import com.intellij.codeInspection.ProblemsHolder;
import com.intellij.openapi.project.Project;
import com.intellij.psi.*;
import org.jetbrains.annotations.NotNull;

import java.util.HashMap;
import java.util.Map;

/**
 * 檢查Service類是否有關聯的Controller API MsgId
 */
public class ServiceLinkInspection extends AbstractBaseJavaLocalInspectionTool {

    @NotNull
    @Override
    public PsiElementVisitor buildVisitor(@NotNull ProblemsHolder holder, boolean isOnTheFly) {
        return new JavaElementVisitor() {
            @Override
            public void visitClass(PsiClass aClass) {
                String className = aClass.getName();
                if (className == null)
                    return;

                // 只檢查Service或ServiceImpl類
                if (!(className.contains("Service") || className.contains("ServiceImpl")))
                    return;

                // 檢查是否已經有ApiMsgId註解
                PsiAnnotation existingAnnotation = aClass.getAnnotation(ApiMsgId.class.getName());
                if (existingAnnotation != null)
                    return;

                // 查找使用此Service的Controller方法
                Map<String, String> controllerMsgIds = findControllerMsgIds(aClass);

                if (!controllerMsgIds.isEmpty()) {
                    holder.registerProblem(
                            aClass.getNameIdentifier() != null ? aClass.getNameIdentifier() : aClass,
                            "Service類可能需要添加來自Controller的ApiMsgId註解",
                            new AddServiceMsgIdQuickFix(controllerMsgIds));
                }
            }

            /**
             * 查找使用此Service的Controller方法及其MsgId
             */
            private Map<String, String> findControllerMsgIds(PsiClass serviceClass) {
                Map<String, String> result = new HashMap<>();

                // 找到所有引用此Service的地方
                PsiReference[] references = serviceClass.getReferences();
                for (PsiReference ref : references) {
                    PsiElement element = ref.getElement();
                    if (element == null)
                        continue;

                    // 查找包含此引用的方法
                    PsiMethod method = findContainingMethod(element);
                    if (method == null)
                        continue;

                    // 檢查方法是否在Controller中
                    PsiClass containingClass = method.getContainingClass();
                    if (containingClass == null)
                        continue;

                    String className = containingClass.getName();
                    if (className == null || !className.contains("Controller"))
                        continue;

                    // 檢查方法是否有ApiMsgId
                    PsiAnnotation apiMsgIdAnnotation = method.getAnnotation(ApiMsgId.class.getName());
                    if (apiMsgIdAnnotation != null) {
                        PsiAnnotationMemberValue value = apiMsgIdAnnotation.findAttributeValue("value");
                        if (value != null) {
                            String msgId = value.getText().replace("\"", "");
                            result.put(method.getName(), msgId);
                        }
                    }
                }

                return result;
            }

            /**
             * 查找包含元素的方法
             */
            private PsiMethod findContainingMethod(PsiElement element) {
                while (element != null && !(element instanceof PsiMethod)) {
                    element = element.getParent();
                }
                return (PsiMethod) element;
            }
        };
    }

    /**
     * 快速修復：添加Service的ApiMsgId註解
     */
    private static class AddServiceMsgIdQuickFix implements LocalQuickFix {
        private final Map<String, String> controllerMsgIds;

        public AddServiceMsgIdQuickFix(Map<String, String> controllerMsgIds) {
            this.controllerMsgIds = controllerMsgIds;
        }

        @NotNull
        @Override
        public String getName() {
            return "添加來自Controller的ApiMsgId註解";
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

            PsiClass aClass = (PsiClass) element.getParent();
            PsiElementFactory factory = JavaPsiFacade.getElementFactory(project);

            // 選擇第一個找到的MsgId
            String msgId = controllerMsgIds.values().iterator().next();

            // 創建註解
            PsiAnnotation annotation = factory.createAnnotationFromText(
                    "@com.cathaybk.codingassistant.annotation.ApiMsgId(\"" + msgId + "\")", aClass);
            aClass.getModifierList().addAfter(annotation, null);
        }
    }
}