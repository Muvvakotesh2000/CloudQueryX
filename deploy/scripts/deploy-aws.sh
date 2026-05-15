#!/bin/bash
set -euo pipefail

# CloudQueryX AWS Deployment Script
# Usage: ./deploy-aws.sh [environment] [region]
#   environment: production (default) or staging
#   region: AWS region (default: us-east-1)

ENVIRONMENT="${1:-production}"
REGION="${2:-us-east-1}"
STACK_NAME="${ENVIRONMENT}-cloudqueryx"
ECR_REPO_NAME="${ENVIRONMENT}-cloudqueryx"
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_DIR="$(cd "${SCRIPT_DIR}/../.." && pwd)"

echo "=== CloudQueryX AWS Deployment ==="
echo "Environment: ${ENVIRONMENT}"
echo "Region: ${REGION}"
echo "Stack: ${STACK_NAME}"
echo ""

# Check prerequisites
command -v aws >/dev/null 2>&1 || { echo "Error: AWS CLI required"; exit 1; }
command -v docker >/dev/null 2>&1 || { echo "Error: Docker required"; exit 1; }

AWS_ACCOUNT_ID=$(aws sts get-caller-identity --query Account --output text --region "${REGION}")
ECR_URI="${AWS_ACCOUNT_ID}.dkr.ecr.${REGION}.amazonaws.com/${ECR_REPO_NAME}"

echo "AWS Account: ${AWS_ACCOUNT_ID}"
echo "ECR URI: ${ECR_URI}"
echo ""

# Step 1: Deploy/update CloudFormation stack
echo ">>> Step 1: Deploying CloudFormation stack..."
aws cloudformation deploy \
    --template-file "${SCRIPT_DIR}/../aws/cloudformation.yml" \
    --stack-name "${STACK_NAME}" \
    --parameter-overrides "EnvironmentName=${ENVIRONMENT}" \
    --capabilities CAPABILITY_IAM \
    --region "${REGION}" \
    --no-fail-on-empty-changeset

echo "Stack deployed successfully."

# Step 2: Build Docker image
echo ""
echo ">>> Step 2: Building Docker image..."
cd "${PROJECT_DIR}"
docker build -t "${ECR_REPO_NAME}:latest" .

# Step 3: Push to ECR
echo ""
echo ">>> Step 3: Pushing to ECR..."
aws ecr get-login-password --region "${REGION}" | \
    docker login --username AWS --password-stdin "${AWS_ACCOUNT_ID}.dkr.ecr.${REGION}.amazonaws.com"

docker tag "${ECR_REPO_NAME}:latest" "${ECR_URI}:latest"
docker tag "${ECR_REPO_NAME}:latest" "${ECR_URI}:$(date +%Y%m%d-%H%M%S)"
docker push "${ECR_URI}:latest"

echo "Image pushed to ECR."

# Step 4: Update ECS service
echo ""
echo ">>> Step 4: Updating ECS service..."
CLUSTER_NAME=$(aws cloudformation describe-stacks \
    --stack-name "${STACK_NAME}" \
    --query "Stacks[0].Outputs[?OutputKey=='ECSClusterName'].OutputValue" \
    --output text --region "${REGION}")

SERVICE_NAME=$(aws cloudformation describe-stacks \
    --stack-name "${STACK_NAME}" \
    --query "Stacks[0].Outputs[?OutputKey=='ECSServiceName'].OutputValue" \
    --output text --region "${REGION}")

aws ecs update-service \
    --cluster "${CLUSTER_NAME}" \
    --service "${SERVICE_NAME}" \
    --force-new-deployment \
    --region "${REGION}" > /dev/null

echo "ECS service update triggered."

# Step 5: Get the URL
echo ""
echo ">>> Deployment complete!"
ALB_DNS=$(aws cloudformation describe-stacks \
    --stack-name "${STACK_NAME}" \
    --query "Stacks[0].Outputs[?OutputKey=='ALBDnsName'].OutputValue" \
    --output text --region "${REGION}")

echo ""
echo "Application URL: http://${ALB_DNS}"
echo ""
echo "Monitor deployment:"
echo "  aws ecs describe-services --cluster ${CLUSTER_NAME} --services ${SERVICE_NAME} --region ${REGION}"
